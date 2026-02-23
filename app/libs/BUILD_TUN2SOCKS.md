# Building tun2socks.aar

Place the compiled `tun2socks.aar` in this directory (`app/libs/`).

## Step 1: Install Android NDK

In **Android Studio** → **Tools** → **SDK Manager** → **SDK Tools** tab →
check **NDK (Side by side)** → click **OK**.

Default install path: `~/Library/Android/sdk/ndk/<version>/`

## Step 2: Build the AAR

```bash
# Install Go tools (Go 1.26+ required)
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

# Find NDK path (after Android Studio installed it)
NDK_PATH="$(ls -d ~/Library/Android/sdk/ndk/*/  | head -1)"
echo "NDK: $NDK_PATH"

# Build from the wrapper package
cd kotkit-basic/scripts/tun2socks-mobile
gomobile bind \
  -target=android/arm64,android/arm \
  -androidapi 26 \
  -ndk "$NDK_PATH" \
  -o ../../app/libs/tun2socks.aar \
  -javapkg tun2socks \
  .
```

## Step 3: Enable the engine in Kotlin

In [Tun2SocksEngine.kt](../src/main/kotlin/com/kotkit/basic/proxy/Tun2SocksEngine.kt), uncomment:

```kotlin
// In start():
tun2socks.Tun2socks.start(tunFd, proxyUrl, "warn")  // uncomment this

// In stop():
tun2socks.Tun2socks.stop()  // uncomment this
```

And remove the TODO comments and the stub `running = true` / `running = false` lines above them.

## Verify generated class name

```bash
jar tf tun2socks.aar | grep class
# Should show: tun2socks/Tun2socks.class (or similar)
```

If the class name is different (e.g. `engine.Engine`), update the calls in `Tun2SocksEngine.kt`.

## Notes

- The wrapper source is in `kotkit-basic/scripts/tun2socks-mobile/`
- Uses tun2socks v2.5.2 (github.com/xjasonlyu/tun2socks/v2)
- Device format for tun2socks engine: `fd://<integer>` where integer is the TUN fd number
- All `app/libs/*.aar` files are included in the Gradle build automatically
