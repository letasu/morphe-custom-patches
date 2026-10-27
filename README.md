# Letasu Patches

本家Morpheの全パッチと、Smart Audio Focus・Hide members videosをまとめたカスタムバンドルです。

## Managerへの登録

[ソースを追加](https://morphe.software/add-source?github=letasu/morphe-custom-patches&name=Letasu%20Patches)

登録URL: `https://github.com/letasu/morphe-custom-patches`

メタデータURL: `https://raw.githubusercontent.com/letasu/morphe-custom-patches/main/patches-bundle.json`

「メタデータがありません」が残る場合は、Managerを最新版へ更新し、ソースの更新を実行してください。保存済みの接続先が古い場合は、このリポジトリURLから追加し直してください。公式Morphe Patchesとの同時適用は行わず、YouTubeにはLetasu Patchesだけを使用します。

パッチ更新後はYouTubeへ再パッチしてインストールしてください。アプリへ自動的に反映されるものではありません。本家v1.48.0以降のソースに対応し、パッチャー2.0対応のManagerが必要です。

## 独自機能

- **Smart Audio Focus**: YouTubeの再生時に外部音楽を一時停止し、Shortsから離れたりアプリをバックグラウンドへ移した際に音声フォーカスを解放します。設定はMorphe → プレイヤー。
- **Hide members videos**: フィードやチャンネルのメンバー限定・先行動画を除外します。設定はMorphe → フィード。初期値はオフです。

## パッチ一覧

<!-- PATCHES_START -->
<!-- PATCHES_END -->

## 本家更新の取り込み・保守

取り込み済みの正式版とコミットは [.letasu/upstream.json](.letasu/upstream.json) に記録します。未リリースのmainやプレリリースは自動追従の対象外です。

1. 作業ツリーを確認し、本家の正式リリースのタグを取得します。例: `git -c fetch.negotiationAlgorithm=noop fetch --no-tags --depth=1 upstream refs/tags/v1.48.0:refs/remotes/upstream/stable`。
2. `python scripts/letasu/sync_upstream.py --ref upstream/stable --tag v1.48.0` でスナップショットを取り込みます。独自クラス・設定文字列・配布情報・ローカルワークフローを維持します。純正クラスへの独自フィールド追加や、MainActivity.onCreate先頭での早期初期化は避けてください。
3. 独自パッチを新しいAPIへ合わせて修正し、`RELEASE_NOTES.md` を更新します。`python scripts/letasu/metadata.py --version X.Y.Z --notes RELEASE_NOTES.md` でバージョンとUTCメタデータを準備します。
4. `python -B -m unittest discover -s tests/release -v` と `python tests/audiofocus/run_tests.py --java-home <JDKパス>` を実行します。
5. タグ作成前に `build_release.yml` を手動実行し、`:patches:buildAndroid :patches:generatePatchesList` が成功することを確認します。生成された `patches-list.json` を取り込み、`python .github/scripts/generate_patches_readme.py letasu/morphe-custom-patches main` で一覧を更新します。
6. メタデータ・独自パッチ2つ・バンドル内容を確認してからコミットとリリースタグをpushします。公開された.mppと同じリリースジョブの成果物のSHA256を照合してください。

`patches-bundle.json` の `created_at` はUTCのLocalDateTimeです。`Z` や `+09:00` を付けるとManagerがデコードできません。未署名の `signature_download_url` は `null` にします。

Crowdinの同期や旧セマンティックリリースの定期ワークフローは復元しません。配布には `build_release.yml` を使用します。継続チェックはこのチャットのCodex定期タスクで管理します。

## ライセンス

本家の著作権表記と追加条項を維持しています。[LICENSE](LICENSE) と [NOTICE](NOTICE) を参照してください。
