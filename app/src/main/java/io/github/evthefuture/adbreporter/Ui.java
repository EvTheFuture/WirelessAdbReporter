package io.github.evthefuture.adbreporter;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Tiny helpers for the code built screens.
 */
final class Ui {

    private Ui() {
    }

    static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }

    /**
     * Vertical content in a scroll view. targetSdk 35+ is always edge to edge, so the
     * content is padded clear of the status bar, navigation bar, cutout and keyboard.
     */
    static ScrollView scrollRoot(Context context, final LinearLayout content) {
        final ScrollView scroll = new ScrollView(context);
        final int pad = dp(context, 16);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int types = WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime();
                Insets bars = insets.getInsets(types);
                content.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
                return WindowInsets.CONSUMED;
            }
        });

        return scroll;
    }

    static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static TextView heading(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(context, 20), 0, dp(context, 4));
        return view;
    }

    static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setPadding(0, dp(context, 8), 0, 0);
        return view;
    }

    /**
     * Paragraph of running text, selectable so commands can be copied.
     */
    static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setLineSpacing(0f, 1.15f);
        view.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return view;
    }

    static TextView mono(Context context) {
        TextView view = new TextView(context);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(context, 8), 0, dp(context, 8));
        return view;
    }

    static Button button(Context context, String text, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }
}
