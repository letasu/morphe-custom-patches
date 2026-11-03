## Letasu Patches v1.0.11

- Shortsからホームやチャンネルへ移動した際に、外部音楽が再開しないことがある問題を修正しました。
- Viewの終了通知中にAndroidがまだ接続中と返す場合でも、通知に基づいて音声フォーカスを解放します。
- 先読みされた未表示のViewや、複数のShortsプレイヤーが重複する場合にも表示中のViewを正しく追跡します。
- 一時フォーカス要求、旧式API、複数のAudioManager、遅延した要求を含め、要求時と一致するフォーカスクライアントを解放します。
- レジスタ範囲形式のAudioManager呼び出しも転送対象に含めました。
- Smart Audio Focusの回帰テストを18ケースへ拡張しました。本家v1.48.0をベースに独自2機能を含むオールインワン構成を維持しています。

Morphe Managerを最新版へ更新し、Letasu Patchesの更新を実行してください。更新が反映されない場合は、ソースを `https://github.com/letasu/morphe-custom-patches` から追加し直してください。

YouTubeへ適用する際は、Letasu Patchesだけを選択し、再パッチしてインストールしてください。

パッチャー2.0対応のManagerが必要です。実機でのYouTube・外部音楽アプリとの連携動作は未確認です。
