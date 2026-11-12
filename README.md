# melolo-adstrip

LSPosed module that removes ads from **Melolo** (`com.worldance.drama`) — the ByteDance-backed short-drama / novel app.

Tested on: POCO X3 Pro (`vayu`), Android 16, KernelSU Next + Zygisk Next + LSPosed v2.2.1, arm64-v8a.

## What it does

Four independent layers, each works on its own:

| Layer | Mechanism | Result |
|---|---|---|
| View killer | hooks `ViewGroup.addView` / `addViewInLayout`, drops ad views before layout; `Activity.onResume` sweeps leftovers to `GONE` | ad views never render |
| SDK neuter | hooks `ClassLoader.loadClass`, neuters `load*` / `show*` / `preload*` / `request*` on ad SDK classes | ads never fill |
| Host sink | hooks `InetAddress.getByName` / `getAllByName`, returns `127.0.0.1` for known ad domains | ad creatives never download |
| Discovery | logs every matched class to `LSPosed-Bridge` | makes the keyword lists growable |

## What the app actually ships

Extracted from the APK on device (`apkanalyzer dex packages`):

```
app ad module   com.worldance.novel.advert.{ads,seriesad,splashad,ugad,webads,advipentry,
                                               chapterlockadapi,readerbottombannerapi}
primary SDK     com.bytedance.sdk.openadsdk.*  (Pangle + mediation adapters)
mediation       admob, mintegral, gam, vungle, inmobi, applovin
also present    com.google.android.gms.ads.*, com.facebook.ads.*
ad views        com.worldance.novel.feature.series.insertpage.nativead.SeriesNativeAdLayout
                ...singlecontroller.holder.SingleTabAdPlaceholderHolder
                com.bytedance.ug.sdk.novel.pendant.DragFrameLayout
```

Confirmed blocked on device:

```
(com.worldance.drama)[fx.oll.melolo,...] ollie-melolo attached -> com.worldance.drama (pid 7649)
(com.worldance.drama)[fx.oll.melolo,...] ollie-melolo blocked view:
    com.bytedance.ug.sdk.novel.pendant.DragFrameLayout
```

### Deliberate non-targets

`com.worldance.novel.advert.*` is **not** in the method-kill list. Melolo gates chapters on ad
callbacks (`chapterlockadapi`); nulling those methods leaves the UI waiting for a callback that
never fires. Its view classes are still blocked, which is where the ads actually appear.

Object-returning methods are never nulled — only `void`, `boolean`, `int`, `long`. Nulling a
returned ad object makes the caller NPE.

Obfuscated SDK guts (`com.facebook.ads.redexgen.*`,
`com.google.android.gms.ads.internal.*`) are skipped: thousands of classes, hook storm, no gain.

## Build

Requires JDK 17, Android SDK 34, Gradle 8.7.

```bash
# from repo root
gradle assembleRelease

# or, if the Xposed maven repo is down, drop api-82.jar into app/libs/
# and swap the dependency:
#   compileOnly files('libs/api-82.jar')
```

Output: `app/build/outputs/apk/release/app-release-unsigned.apk`

CI: `docs/ci-build.yml` is a ready GitHub Actions workflow. GitHub rejects workflow files pushed
by a device-flow token without the `workflow` scope, so copy it to `.github/workflows/build.yml`
through the web UI (or re-auth with `gh auth refresh -s workflow`) to enable it.

Sign it before installing:

```bash
zipalign -f -p 4 app-release-unsigned.apk melolo-adstrip.apk
apksigner sign --ks your.keystore --ks-key-alias <alias> --out melolo-adstrip.apk melolo-adstrip.apk
```

## Install

```bash
adb install -r melolo-adstrip.apk
```

1. LSPosed Manager → Modules → enable **ollie melolo ad strip**
2. Scope → tick **Melolo** (`com.worldance.drama`)
3. Force-stop Melolo, reopen

Manifest side (`xposedmodule`, `xposeddescription`, `xposedminversion`, `xposedscope`) is already
wired; `res/values/arrays.xml` must stay in sync with `MeloloAdStrip.TARGETS`.

## Tuning

```bash
adb logcat -s LSPosed-Bridge
```

| Log line | Meaning |
|---|---|
| `attached -> com.worldance.drama` | module injected, scope correct |
| `blocked view: <class>` | layer 1 dropped an ad view |
| `hid view: <class>` | layer 1 hid a view that was already attached |
| `neutered: <class>` | layer 2 killed load/show on an ad SDK class |
| `sink <host>` | layer 3 redirected an ad domain |
| `attach failed: <err>` | a hook threw during attach |

Ad still visible but nothing in the log → its class name is not in `AD_CLASS_KEYWORDS` yet. Find it
in the app's dex (`apkanalyzer dex packages --defined-only base.apk`) and add the fragment.

## Limitations

- **Root required.** LSPosed needs Zygisk (Magisk / KernelSU Next + Zygisk Next). There is no
  no-root path: LSPatch v0.6 dies on Android 16 with `Failed to init lsplant` and
  `NoSuchFieldError: ActivityThread$AppBindData#compatInfo`, which kills the app at
  `Application` instantiation.
- Rewarded flows: if an episode is gated on "watch ad to unlock", neutering `show` on the rewarded
  class means the reward callback may never fire. Drop `showRewarded*` from the kill set in that case
  and let the view killer + host sink do the work.
- `an.facebook.com` is in the sink list; `graph.facebook.com` deliberately is not (login path).
- `imasdk.googleapis.com` is in the sink list; if the player breaks, remove it.

## License

MIT
