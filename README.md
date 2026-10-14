# amadeus-mobile

红莉栖的安卓本体。设计见 [docs/mobile-apk-plan.md](docs/mobile-apk-plan.md)。

## 目录

| 目录 | 内容 |
|---|---|
| `control/` | 控制面 `amadeus-control`（Node，pi-durable），stdio 帧协议 |
| `payload/` | payload 组装脚本：Termux Node 运行时 + 控制面 |
| `web/` | WebView 前端（TypeScript，esbuild 打包），含 emote 立绘渲染 |
| `scripts/` | `fetch-deps.mjs`：下载锁定版本的 sherpa-onnx 与识别模型，复制 amadeus-next 的 emote 素材 |
| `android/` | Kotlin 宿主，包名 `com.amadeus.mobile` |

## 构建

依赖：Node ≥ 22.19、JDK 21、Android SDK（platform 37、build-tools 36），以及同级目录下的 amadeus-next（emote 素材，需先在其中打开过一次场景以生成烘焙模型）。
emote 素材与第三方二进制都不进 git，由脚本生成到 `build/` 与 `android/app/libs/`。

```sh
(cd control && npm ci && npm test)
node scripts/fetch-deps.mjs        # sherpa-onnx AAR、识别模型、emote 素材
node payload/assemble.mjs          # 产出 payload/build/{assets,jniLibs}
(cd web && npm ci && npm run build)
cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`android/local.properties` 需写入 `sdk.dir=<Android SDK 路径>`；`gradle.properties` 里的 JDK 路径按本机调整。
