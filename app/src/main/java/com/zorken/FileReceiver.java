package com.zorken;

import android.content.*;
import java.util.*;

public class FileReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(final Context c, Intent i) {
        final SharedPreferences sp = Common.prefs(c);
        String a = i.getAction();
        if (Common.ACT_LOG.equals(a)) {
            String m = i.getStringExtra("msg");
            if (m != null) AppLog.add(c, "module", m.length() > 300 ? m.substring(0, 300) : m);
            return;
        }
        if (Common.ACT_PONG.equals(a)) {
            AppLog.add(c, "module", "Module responded, version " + i.getIntExtra("ver", 0));
            sp.edit().putLong("pong", System.currentTimeMillis())
                .putInt("modver", i.getIntExtra("ver", 0))
                .putBoolean("modhas", i.getBooleanExtra("has", false)).apply();
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) { AppLog.add(c, "boot", "Device started"); EncryptJob.schedule(c, false); return; }
        if (Common.ACT_ACK.equals(a)) { ack(c, sp, i); return; }
        if (!Common.ACT_HIDE_FILES.equals(a)) return;
        AppLog.add(c, "hide", "Module requested file hiding");

        SharedPreferences.Editor ed = sp.edit().putBoolean("hidden", true);
        if (sp.getString("hash", null) == null && sp.getStringSet("paths", new HashSet<String>()).isEmpty())
            ed.putStringSet("paths", paths(i.getStringExtra("paths")));
        ed.apply();
        final PendingResult pr = goAsync();
        new Thread(() -> { try { Shell.hideSync(c); EncryptJob.schedule(c); } finally { pr.finish(); } }).start();
    }

    static void ack(Context ctx, SharedPreferences sp, Intent i) {
        String nonce = i.getStringExtra("nonce"), pend = sp.getString("pendnonce", null);
        if (nonce == null || !nonce.equals(pend)) return;
        boolean ok = i.getBooleanExtra("ok", false);
        if (!ok) AppLog.add(ctx, "module", "Command refused (incorrect PIN or not allowed)");
        SharedPreferences.Editor ed = sp.edit().remove("pendnonce");
        if (ok && i.hasExtra("hash")) {
            ed.putString("hash", i.getStringExtra("hash"));
            ed.putStringSet("apps", split(i.getStringExtra("apps"), ","));
            ed.putStringSet("paths", paths(i.getStringExtra("paths")));
            ed.putInt("th", Common.limit(i.getIntExtra("threshold", 2)));
            ed.putBoolean("hidden", i.getBooleanExtra("hidden", false));
        }
        ed.putInt("ack_wait", i.getIntExtra("wait", 0));
        ed.putString("ack", nonce + ":" + (ok ? "1" : "0")).commit();
    }

    static Set<String> split(String s, String sep) {
        Set<String> out = new HashSet<>();
        if (s != null) for (String x : s.split(sep)) if (!x.isEmpty()) out.add(x);
        return out;
    }

    static Set<String> paths(String s) {
        Set<String> out = new HashSet<>();
        for (String p : split(s, "\n")) if (Shell.resolve(p) != null) out.add(p);
        return out;
    }
}
