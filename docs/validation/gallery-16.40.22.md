# Gallery 16.40.22 Validation

Validation date: 2026-08-01 (Asia/Shanghai)

## Device And Input

- Device model: `PKX110`
- Gallery package: `com.coloros.gallery3d`
- Gallery version name: `16.40.22`
- Gallery version code: `16040022`
- `base.apk` SHA-256: `4955BA42F99ED4B20E9CA0BBCC95FEDB949DA75F474FB395153192D8527C4A17`
- `classes17.dex` SHA-256: `4F0BE99C37444B770BAC91E38337BC2A897FAD78E8560406FF15374BB87DD7FA`

The APK and DEX were pulled from the connected test device and kept outside the repository. No
proprietary Gallery binary is committed.

## Structural Locator

The optional real-input test was run with the extracted `classes17.dex`:

```powershell
$dexPath = Join-Path $env:TEMP 'coloros-feiniu-bridge-gallery-164022/classes17.dex'
& gradle ':app:testDebugUnitTest' `
    '--tests' 'io.github.colorosfeiniu.bridge.RealGalleryDexValidationTest' `
    "-Dgallery.dex.path=$dexPath" `
    '--rerun-tasks'
```

Result:

```text
tests=1, skipped=0, failures=0, errors=0
TokenDecryptorLocator.locate(classes17.dex) = com.oplus.aiunit.vision.qp80
```

## Device Hook

The freshly built debug APK replaced the existing module without uninstalling it. After force
stopping and launching Gallery, the new LSPosed module log entries were:

```text
ColorOSFeiniuBridge: installed for com.coloros.gallery3d class=com.oplus.aiunit.vision.qp80 via=known-name
ColorOSFeiniuBridge: prefix fallback supplied source=apk-dex len=33
```

This confirms that Gallery 16.40.22 loads the expected target and receives the APK-derived prefix
fallback on the physical device. The validation log contains no token or prefix value.
