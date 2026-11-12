package fx.oll.melolo;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        StringBuilder sb = new StringBuilder();
        sb.append("ollie - melolo ad strip\n\n");
        sb.append("layers\n");
        sb.append("  - view killer: addView drops ad views, onResume sweeps leftovers\n");
        sb.append("  - sdk neuter: show/load on matching classes become no-ops\n");
        sb.append("  - host sink: ad domains resolve to 127.0.0.1\n");
        sb.append("  - discovery: ad-looking classes logged for tuning\n\n");
        sb.append("scope\n");
        for (String p : MeloloAdStrip.TARGETS) {
            sb.append("  ").append(p).append('\n');
        }
        sb.append("\nsetup\n");
        sb.append("  1. enable the module in LSPosed\n");
        sb.append("  2. tick Melolo in the scope list\n");
        sb.append("  3. force-stop Melolo\n\n");
        sb.append("tune\n");
        sb.append("  filter logs with:  adb logcat -s LSPosed-Bridge\n");

        TextView tv = new TextView(this);
        tv.setPadding(56, 56, 56, 56);
        tv.setTextSize(13);
        tv.setText(sb.toString());

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }
}
