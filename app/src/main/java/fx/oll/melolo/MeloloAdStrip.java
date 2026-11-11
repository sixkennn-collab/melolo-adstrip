package fx.oll.melolo;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module: strips ads out of Melolo (com.worldance.drama).
 *
 * Confirmed targets on device, Android 17 / POCO X3 Pro, Melolo 5.5.2 (Play Store):
 *   - app ad module   com.worldance.novel.advert.*  (ads, seriesad, splashad, ugad, webads, ...)
 *   - primary SDK     com.bytedance.sdk.openadsdk.* (Pangle + mediation adapters)
 *   - secondary SDKs  com.google.android.gms.ads.*, com.facebook.ads.*
 *
 * Layers:
 *   1. view hider   - ad views are collapsed to 0x0 and set GONE, never refused.
 *                     v1.0 refused addView, which broke layout inflation:
 *                     SeriesContentMaskView.<init> NPEs on findViewById when a
 *                     child it expects was never attached.
 *   2. SDK neuter   - load and show methods on ad SDK classes become no-ops
 *   3. host sink    - ad domains resolve to 127.0.0.1
 *   4. discovery    - every matched class is logged for tuning
 */
public class MeloloAdStrip implements IXposedHookLoadPackage {

    private static final String TAG = "ollie-melolo ";

    public static final Set<String> TARGETS = new HashSet<>(Arrays.asList(
            "com.worldance.drama"
    ));

    private static final boolean DEBUG_ALL_APPS = false;
    private static final boolean LOG_DISCOVERY = true;

    /**
     * Classes under these prefixes get their load/show methods neutered.
     * SDK code only: the app's own ad module is deliberately absent here, because
     * Melolo gates chapters on ad callbacks and killing those would hang the UI.
     */
    private static final String[] SDK_PACKAGES = {
            "com.bytedance.sdk.openadsdk.",
            "com.bytedance.sdk.pangle.",
            "com.pangle.",
            "com.google.android.gms.ads.",
            "com.facebook.ads.",
            "com.mbridge.",
            "com.mintegral.",
            "com.applovin.",
            "com.unity3d.ads.",
            "com.unity3d.services.ads.",
            "com.ironsource.",
            "com.vungle.",
            "com.chartboost.",
            "com.startapp.",
            "com.inmobi.",
            "com.smaato.",
            "com.adcolony.",
            "com.fyber.",
            "com.anythink.",
            "com.toponad.",
            "com.admost.",
            "com.appnext."
    };

    /** Obfuscated SDK guts: thousands of classes, hook storm for little gain.
     *  View hiding plus the DNS sink in layer 1/3 already covers these. */
    private static final String[] HOOK_SKIP = {
            "com.facebook.ads.redexgen.",
            "com.google.android.gms.ads.internal."
    };

    /** App-side ad code: view classes here are hidden, methods are left alone. */
    private static final String[] APP_AD_PACKAGES = {
            "com.worldance.novel.advert.",
            "com.worldance.novel.feature.series.insertpage.nativead."
    };

    /**
     * Whole words that mean "this view is an ad", matched against the class name
     * split on non-alphanumerics. Substring matching was the v1.0 bug: plain
     * text views with ids like "series_head_ad_tag" got dropped out of the layout.
     */
    private static final Set<String> AD_WORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "ad", "ads", "adv", "advert", "adverts", "advertise", "advertisement",
                    "adview", "adviews", "adsview", "adcontainer", "adlayout", "adbanner",
                    "adslot", "adbox", "adspace", "adspot", "adholder", "adwrapper",
                    "adstub", "adframe", "admask", "adlayer", "adcard", "adfeed",
                    "nativead", "splashad", "bannerad", "feedad", "appopenad",
                    "interstitial", "rewarded", "adsdk", "adloader", "admanager",
                    "adrequest", "advip"
            )));

    /** Non-container views are only hidden on a strong id match, never a weak one. */
    private static final Set<String> STRONG_AD_WORDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "nativead", "splashad", "bannerad", "feedad", "adview", "adcontainer",
                    "adlayout", "adbanner", "adslot", "adholder", "adwrapper", "adframe",
                    "admask", "nativeadview", "rewarded"
            )));

    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "adservice.google.com", "imasdk.googleapis.com",
            "applovin.com", "unityads.unity3d.com", "ironsrc.com",
            "supersonicads.com", "mbridge.com", "mintegral.com",
            "pangolin-sdk-toutiao.com", "pglstatp-toutiao.com", "gromore.com",
            "pangle.io", "gdt.qq.com", "pgdt.gtimg.cn", "pgdt.gtimg.com",
            "adsmind.gdtimg.com", "vungle.com", "chartboost.com",
            "startappservice.com", "startapp.com", "anythinktech.com",
            "toponad.com", "adcolony.com", "fyber.com", "inmobi.com",
            "smaato.net", "admost.com", "appnext.com", "mopub.com",
            "criteo.com", "criteo.net", "adnxs.com", "appnexus.com",
            "amazon-adsystem.com", "moatads.com", "adsafeprotected.com",
            "an.facebook.com", "adn.ebay.com"
    };

    private static final String[] EXCLUDE = {
            "android.", "java.", "kotlin.", "dalvik.", "com.android.",
            "de.robv.android.xposed", "fx.oll.melolo"
    };

    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        boolean target = TARGETS.contains(lp.packageName);
        if (!target && !DEBUG_ALL_APPS) return;

        try {
            hookAddView();
            hookActivityResume();
            hookWebView();
            hookDns();
            hookClassLoader();
            log("attached -> " + lp.packageName + " (pid " + android.os.Process.myPid() + ")");
        } catch (Throwable t) {
            log("attach failed: " + t);
        }
    }

    /* ---------------------------------------------------------------- layer 1 */

    /**
     * Ad views are collapsed in place, never refused. A refused addView makes the
     * caller's layout miss a child it later looks up by id, and that is an
     * immediate NullPointerException during inflation.
     */
    private void hookAddView() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args == null || p.args.length == 0) return;
                if (!(p.args[0] instanceof View)) return;
                final View v = (View) p.args[0];
                if (!isAdView(v)) return;
                if (LOG_DISCOVERY) log("hid view: " + describe(v));
                try {
                    v.setVisibility(View.GONE);
                } catch (Throwable ignored) {
                }
                try {
                    v.post(new Runnable() {
                        @Override
                        public void run() {
                            collapse(v);
                        }
                    });
                } catch (Throwable ignored) {
                }
            }
        };
        XposedBridge.hookAllMethods(ViewGroup.class, "addView", h);
        XposedBridge.hookAllMethods(ViewGroup.class, "addViewInLayout", h);
    }

    private void hookActivityResume() {
        XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Activity a = (Activity) p.thisObject;
                    if (a.getWindow() == null) return;
                    View root = a.getWindow().getDecorView();
                    if (root != null) strip(root, 0);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void strip(View v, int depth) {
        if (v == null || depth > 40) return;
        if (depth > 0 && isAdView(v)) {
            if (LOG_DISCOVERY) log("hid swept view: " + describe(v));
            collapse(v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                try {
                    strip(g.getChildAt(i), depth + 1);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Zero size and invisible, but still a child: findViewById keeps working. */
    private static void collapse(View v) {
        try {
            v.setVisibility(View.GONE);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                lp.width = 0;
                lp.height = 0;
                v.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isAdView(View v) {
        if (v == null) return false;
        String cls = v.getClass().getName();
        if (isExcluded(cls)) return false;

        if (startsWithAny(cls, SDK_PACKAGES)) return true;
        if (startsWithAny(cls, APP_AD_PACKAGES)) return true;
        if (hasAnyWord(classNameOf(cls), AD_WORDS)) return true;

        // Id fallback. Containers can be matched on any ad word; a bare TextView
        // or ImageView only on a strong one, otherwise real UI gets hidden.
        int id = v.getId();
        if (id == View.NO_ID) return false;
        String name;
        try {
            name = v.getResources().getResourceEntryName(id);
        } catch (Throwable t) {
            return false;
        }
        if (name == null) return false;
        Set<String> words = wordsOf(name);
        if (hasAnyWord(words, AD_WORDS)) {
            if (v instanceof ViewGroup) return true;
            return hasAnyWord(words, STRONG_AD_WORDS);
        }
        return false;
    }

    private static String classNameOf(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    private static Set<String> wordsOf(String raw) {
        String low = raw.toLowerCase(Locale.ROOT);
        String[] parts = low.split("[^a-z0-9]+");
        return new HashSet<>(Arrays.asList(parts));
    }

    private static boolean hasAnyWord(String name, Set<String> words) {
        return hasAnyWord(wordsOf(name), words);
    }

    private static boolean hasAnyWord(Set<String> tokens, Set<String> words) {
        for (String t : tokens) {
            if (t.isEmpty()) continue;
            if (words.contains(t)) return true;
        }
        return false;
    }

    private static String describe(View v) {
        String id = "?";
        try {
            int rid = v.getId();
            if (rid != View.NO_ID) id = v.getResources().getResourceEntryName(rid);
        } catch (Throwable ignored) {
        }
        return v.getClass().getName() + " id=" + id;
    }

    /* ---------------------------------------------------------------- layer 2 */

    private void hookClassLoader() {
        XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam p) {
                Object r = p.getResult();
                if (!(r instanceof Class<?>)) return;
                Class<?> c = (Class<?>) r;
                String name = c.getName();
                if (isExcluded(name)) return;
                if (startsWithAny(name, HOOK_SKIP)) return;
                if (!startsWithAny(name, SDK_PACKAGES)) return;
                try {
                    hookAdClass(c);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(ClassLoader.class, "loadClass", h);
        } catch (Throwable t) {
            log("classloader hook failed: " + t);
        }
    }

    private void hookAdClass(Class<?> c) {
        Method[] methods;
        try {
            methods = c.getDeclaredMethods();
        } catch (Throwable t) {
            return;
        }
        boolean hookedOne = false;
        for (final Method m : methods) {
            String n = m.getName();
            if (!isKillMethodName(n)) continue;
            Class<?> rt = m.getReturnType();
            if (rt != void.class && rt != boolean.class
                    && rt != int.class && rt != long.class) {
                continue; // object returns stay intact: nulling them NPEs callers
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        Class<?> t = m.getReturnType();
                        if (t == void.class) {
                            p.setResult(null);
                        } else if (t == boolean.class) {
                            p.setResult(false);
                        } else if (t == int.class) {
                            p.setResult(0);
                        } else if (t == long.class) {
                            p.setResult(0L);
                        }
                    }
                });
                hookedOne = true;
            } catch (Throwable ignored) {
            }
        }
        if (hookedOne && LOG_DISCOVERY) log("neutered: " + c.getName());
    }

    private static boolean isKillMethodName(String n) {
        if (n.startsWith("load") || n.startsWith("show")) return true;
        if (n.startsWith("preload") || n.startsWith("request")) return true;
        if (n.equals("present") || n.equals("render") || n.equals("bindAd")) return true;
        return false;
    }

    /* ---------------------------------------------------------------- layer 3 */

    private void hookDns() {
        XC_MethodHook byName = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                String host = firstStringArg(p.args);
                if (host == null || !isAdHost(host)) return;
                try {
                    p.setResult(InetAddress.getByAddress(host, LOOPBACK));
                    log("sink " + host);
                } catch (Throwable ignored) {
                }
            }
        };
        XC_MethodHook allByName = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                String host = firstStringArg(p.args);
                if (host == null || !isAdHost(host)) return;
                try {
                    p.setResult(new InetAddress[]{
                            InetAddress.getByAddress(host, LOOPBACK)});
                    log("sink " + host);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(InetAddress.class, "getByName", byName);
            XposedBridge.hookAllMethods(InetAddress.class, "getAllByName", allByName);
        } catch (Throwable t) {
            log("dns hook failed: " + t);
        }
    }

    private void hookWebView() {
        try {
            XposedHelpers.findAndHookMethod(WebView.class, "loadUrl",
                    String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            Object a = p.args.length > 0 ? p.args[0] : null;
                            if (!(a instanceof String)) return;
                            String url = (String) a;
                            String host = hostOf(url);
                            if (host != null && isAdHost(host)) {
                                log("blocked url " + url);
                                p.setResult(null);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("webview hook failed: " + t);
        }
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstStringArg(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a instanceof String) return (String) a;
        }
        return null;
    }

    /* ---------------------------------------------------------------- helpers */

    private static boolean startsWithAny(String s, String[] prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    private static boolean isExcluded(String name) {
        for (String e : EXCLUDE) {
            if (name.startsWith(e)) return true;
        }
        return false;
    }

    private static boolean isAdHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : AD_HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }
}
