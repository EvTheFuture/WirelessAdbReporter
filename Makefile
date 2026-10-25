# Wireless ADB Reporter
#
# One entry point for everything this repository builds: the APK, the checks
# on the receiver and the build scripts, and the release files in dist/. It
# also covers the phone side: install, grant and provision over adb.
#
# Recipes need real tabs, that is a make rule, not a style choice here.

SHELL := /bin/sh

# The version the whole project follows, Gradle and build.sh read the same file
VERSION := $(shell tr -d ' \n' < VERSION)

# Gradle 8.14 runs on JDK 17 to 24 only, a newer JDK fails while compiling
# the build scripts ("Unsupported class file major version 69" is JDK 25)
JDK_MIN := 17
JDK_MAX := 24

# Where installed JDKs are looked for: Ubuntu, Alpine and macOS layouts
JVM_DIRS ?= /usr/lib/jvm/* /Library/Java/JavaVirtualMachines/*/Contents/Home

# The JAVA_HOME from the environment if it is in range, otherwise the newest
# installed JDK in range. The major version comes from the JDK's release file.
# JAVA_HOME=... on the make command line always wins
JAVA_HOME := $(shell \
    for dir in "$$JAVA_HOME" $$(ls -d $(JVM_DIRS) 2>/dev/null | sort -rV); do \
        major=$$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$$dir/release" 2>/dev/null); \
        if [ -n "$$major" ] && [ "$$major" -ge $(JDK_MIN) ] && [ "$$major" -le $(JDK_MAX) ] \
            && [ -x "$$dir/bin/javac" ]; then \
            echo "$$dir"; \
            break; \
        fi; \
    done)

# Refuses to start Gradle without a JDK in range, also one given on the command line
define check_jdk
	@test -n "$(JAVA_HOME)" || { \
	    echo "No JDK $(JDK_MIN) to $(JDK_MAX) found, Gradle 8.14 cannot run on a newer one."; \
	    echo "Install one, e.g. sudo apt install openjdk-21-jdk, or set JAVA_HOME=..."; \
	    exit 1; }
	@major=$$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$(JAVA_HOME)/release" 2>/dev/null); \
	if [ -z "$$major" ] || [ "$$major" -lt $(JDK_MIN) ] || [ "$$major" -gt $(JDK_MAX) ]; then \
	    echo "JAVA_HOME=$(JAVA_HOME) is JDK $${major:-unknown}, Gradle 8.14 needs $(JDK_MIN) to $(JDK_MAX)"; \
	    exit 1; \
	fi
endef

# Where the Android SDK lives. Ubuntu packages put it here
ANDROID_HOME ?= /usr/lib/android-sdk

PKG := io.github.evthefuture.adbreporter
NAME := wireless-adb-reporter
KEYSTORE := keystore/adbreporter.p12

APK_DEBUG := app/build/outputs/apk/debug/app-debug.apk
APK_RELEASE := app/build/outputs/apk/release/app-release.apk
APK_DIRECT := out/$(NAME)-$(VERSION).apk

# Phone setup for make provision, only the ones given are sent
URL ?=
TOKEN ?=
DEVICE_ID ?=
SSIDS ?=
PROFILE ?=
REMOVE_PROFILE ?=
AUTO ?= true

# Receiver for make serve
PORT ?= 8765

.PHONY: help all android apk release direct install grant provision setup serve check src dist dist-direct \
    clean distclean

help:
	@echo "Wireless ADB Reporter $(VERSION)"
	@echo
	@echo "  make all                 checks, the release APK and the files in dist/"
	@echo "  make android             the debug APK, same as make apk"
	@echo "  make release             the release APK, signed with the project key"
	@echo "  make direct              the APK without Gradle, straight from the SDK tools (build.sh)"
	@echo "  make install             install the debug APK on the connected phone"
	@echo "  make grant               grant secure settings, location and notifications"
	@echo "  make provision           configure the app, PROFILE= URL= TOKEN= DEVICE_ID= SSIDS=a,b AUTO=true REMOVE_PROFILE="
	@echo "  make setup               install, grant and provision in one go"
	@echo "  make serve               run the receiver here, PORT=$(PORT) TOKEN= with adb connect"
	@echo "  make check               syntax check the receiver and build.sh, validate VERSION"
	@echo "  make src                 only dist/$(NAME)-src.zip, no APK"
	@echo "  make dist                dist/$(NAME)-$(VERSION).apk and $(NAME)-src.zip"
	@echo "  make dist-direct         the same, with the APK from make direct"
	@echo "  make clean               remove build output, keep the caches"
	@echo "  make distclean           also remove the Gradle cache and local.properties"
	@echo
	@echo "  JAVA_HOME    $(or $(JAVA_HOME),none in range $(JDK_MIN) to $(JDK_MAX))"
	@echo "  ANDROID_HOME $(ANDROID_HOME)"

all: check release dist
	@echo "Built everything at version $(VERSION)"

# Cheap, so they run before anything is built
check:
	@python3 -c "import ast, sys; ast.parse(open(sys.argv[1]).read())" server/adb-reporter-server.py \
	    && echo "adb-reporter-server.py parses"
	@sh -n build.sh && echo "build.sh parses"
	@sh -n server/adb-reporter.initd && echo "adb-reporter.initd parses"
	@sh -n server/openwrt/adb && echo "openwrt/adb parses"
	@echo "$(VERSION)" | grep -Eq '^[0-9]{1,2}(\.[0-9]{1,2}){0,3}$$' \
	    || { echo "VERSION must be up to four dot separated numbers below 100, got '$(VERSION)'"; exit 1; }
	@echo "Version $(VERSION)"

android: apk

apk: local.properties $(KEYSTORE)
	$(check_jdk)
	JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleDebug
	@echo "APK: $(APK_DEBUG)"

release: local.properties $(KEYSTORE)
	$(check_jdk)
	JAVA_HOME="$(JAVA_HOME)" ./gradlew assembleRelease
	@echo "APK: $(APK_RELEASE)"

# No Gradle and no Maven downloads, only the SDK's own tools
direct: $(KEYSTORE)
	ANDROID_HOME="$(ANDROID_HOME)" ./build.sh

# Written once, and again whenever the SDK moves
local.properties:
	@test -d "$(ANDROID_HOME)" || { echo "No Android SDK at $(ANDROID_HOME), set ANDROID_HOME"; exit 1; }
	echo "sdk.dir=$(ANDROID_HOME)" > $@

# The key every build is signed with. Kept in the repository so rebuilt APKs
# install over the old ones and the granted permissions survive
$(KEYSTORE):
	mkdir -p $(dir $@)
	keytool -genkeypair -noprompt -keystore $@ -storetype PKCS12 \
	    -alias adbreporter -storepass adbreporter -keypass adbreporter \
	    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Wireless ADB Reporter"

install: apk
	adb install -r $(APK_DEBUG)

# Background location needs fine location first, so the order matters.
# Notifications are a permission from Android 13 only, so a failure there is
# ignored on older phones
grant:
	adb shell pm grant $(PKG) android.permission.WRITE_SECURE_SETTINGS
	adb shell pm grant $(PKG) android.permission.ACCESS_FINE_LOCATION
	adb shell pm grant $(PKG) android.permission.ACCESS_BACKGROUND_LOCATION
	-adb shell pm grant $(PKG) android.permission.POST_NOTIFICATIONS
	@echo "Granted, switch Location on in quick settings if it is off"

# adb shell joins its arguments into one command line on the phone, so every
# value is single quoted there to keep spaces in SSIDs intact
provision:
	adb shell "am start -n $(PKG)/.ProvisionActivity \
	    $(if $(REMOVE_PROFILE),--es remove_profile '$(REMOVE_PROFILE)') \
	    $(if $(PROFILE),--es profile '$(PROFILE)') \
	    $(if $(URL),--es url '$(URL)') \
	    $(if $(TOKEN),--es token '$(TOKEN)') \
	    $(if $(DEVICE_ID),--es device_id '$(DEVICE_ID)') \
	    $(if $(SSIDS),--es ssids '$(SSIDS)') \
	    --ez auto $(AUTO)"

setup: install grant provision

serve:
	./server/adb-reporter-server.py --port $(PORT) --auto-connect $(if $(TOKEN),--token '$(TOKEN)')

# The source zip has no version in its name and its files at the root,
# without a top folder
src:
	mkdir -p dist
	rm -f dist/$(NAME)-src.zip
	zip -qr dist/$(NAME)-src.zip . \
	    -x '.git/*' '.gradle/*' 'build/*' 'app/build/*' 'out/*' 'dist/*' \
	       'local.properties' '*/__pycache__/*'
	@echo "dist/$(NAME)-src.zip"

# The release files, the APK next to the source zip
dist: release src
	cp $(APK_RELEASE) dist/$(NAME)-$(VERSION).apk
	@echo "dist/$(NAME)-$(VERSION).apk"

# Same, with the APK from make direct
dist-direct: direct src
	cp $(APK_DIRECT) dist/$(NAME)-$(VERSION).apk
	@echo "dist/$(NAME)-$(VERSION).apk"

clean:
	rm -rf app/build build out dist

distclean: clean
	rm -rf .gradle local.properties
