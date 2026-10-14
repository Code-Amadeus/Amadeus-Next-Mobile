# amadeus-mobile

红莉栖的安卓本体。设计见 [docs/mobile-apk-plan.md](docs/mobile-apk-plan.md)。

## 目录

| 目录 | 内容 |
|---|---|
| `control/` | 控制面 `amadeus-control`（Node，pi-durable），stdio 帧协议 |
| `payload/` | payload 组装脚本：Termux Node 运行时 + 控制面 |
| `android/` | Kotlin 宿主，包名 `com.amadeus.mobile` |

## 构建

依赖：Node ≥ 22.19、JDK 21、Android SDK（platform 35、build-tools 36）。

```sh
(cd control && npm ci && npm test)
node payload/assemble.mjs          # 产出 payload/build/{assets,jniLibs}
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`android/local.properties` 需写入 `sdk.dir=<Android SDK 路径>`；`gradle.properties` 里的 JDK 路径按本机调整。
