# OsmoTake Android版

Game Controller（GC）のWebSocketとDJI Osmo Nanoを接続し、試合状態に合わせて録画を開始・停止するAndroidアプリです。Huawei P20 Pro HW-01K（Android 9 / API 28）を主対象とし、`minSdk 27`で構成しています。

## ビルド

Android Studio付属JDK 25、Android SDK 37、Gradle 9.6.0、Android Gradle Plugin 9.4.1を使用します。

```powershell
cd android
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest assembleDebug
```

APKは`app/build/outputs/apk/debug/app-debug.apk`に生成されます。

Android Studioを使う場合は、このリポジトリ全体ではなく`android`フォルダーをプロジェクトとして開きます。ADBでdebug APKを入れる場合は次を実行します。

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 操作

1. 端末をGCと同じWi-Fiへ接続し、GCサーバーのTCPポート`8081`へ到達できることを確認します。インターネット接続がないWi-Fiでも接続を維持してください。EMUIがモバイル回線や別のWi-Fiへ自動切替する場合は、その機能を無効にします。
2. GCのURLを入力して「GC URLを保存して接続」を押します。例: `ws://192.168.1.20:8081/api/control`
3. Osmo Nanoの電源を入れ、DJI Mimoとの接続を切ります。「Nano検索」を押します。Android 9ではBLE検索のため位置情報権限と位置情報サービスが必要です。BluetoothがOFFなら有効化画面を表示します。
4. 一覧からOsmo Nanoを選び「接続」を押します。初回はNanoをVision Dockへ取り付け、画面に表示される`OSMOCTRL`を承認します。OSのBluetoothペアリングは不要です。
5. 「GC状態と録画を自動連動」をONにします。

HW-01Kの「設定」から、このアプリの自動起動・バックグラウンド実行を手動で許可し、電池の最適化対象から外してください。EMUIの省電力設定名は端末の更新状態で異なります。Foreground Service、部分WakeLock、Wi-Fi lockを使用しますが、メーカー独自の強制終了をアプリだけで解除することはできません。

`matchState.gameState.type`が`RUNNING`のときだけ録画開始を要求します。有効なほかの試合状態では停止を要求します。`matchState`が欠落または`null`のメッセージは状態を変更しません。GC再接続後は新しい`matchState`を受け取るまで過去のRUNNINGを再利用しません。GC切断を検出してから5秒経過すると、安全のため停止を要求します。WebSocketのping間隔は15秒なので、物理的な通信断から検出までには追加時間がかかる場合があります。

「手動録画開始」と「手動停止」は、どちらも自動連動を解除してから実行します。手動録画開始はNanoの制御準備が完了している場合だけ送信し、未接続時の開始予約はしません。BLEが切れて再接続しても、手動開始を自動で再送しません。手動停止は安全側の意図として再接続後も保持します。「停止して終了」は停止コマンドを試行してからBLEとGCを切断します。戻る操作や画面OFFではForeground Serviceが動作を継続します。通知権限を拒否しても制御を妨げませんが、対応OSでは継続動作を確認しやすくするため通知を許可してください。

## 状態確認

画面にはGC、BLE、録画の状態と最新300件のログを表示します。録画コマンドのACKとカメラの録画状態は区別し、「カメラ確認済み」はfull state pushを受信した場合だけ表示します。15秒以内に一致するstate pushがなければログへ未確認と表示します。

スマートフォンとNano間のBLE自体が切れた場合、停止コマンドをNanoへ届ける手段がないため、録画停止は保証できません。再接続できた場合は保持している安全側の停止意図を再送します。

通信はForeground Service内の単一executorで直列化しています。WebSocketはping/pongと指数バックオフ再接続、BLEは1秒keepalive、2.5秒状態取得、0.12秒間隔のwrite-without-responseを使用します。画面OFF中も処理を維持するため部分WakeLockを保持します。

HW-01KでAPKのインストール・起動、日本語UI、モックGCによる`RUNNING`・非RUNNING・`null matchState`・WebSocket再接続、約1分の画面OFF中のForeground Service継続を確認しています。実機Osmo Nanoへの接続・承認・録画操作は実施していません。移植元プロトコルとMITライセンスはAPK内の`assets/THIRD_PARTY_NOTICES.md`に同梱しています。
