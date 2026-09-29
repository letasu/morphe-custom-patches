package app.morphe.extension.youtube.patches.components;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Filters out members-only and members-first video cards from feeds (Home, Subscriptions, Search, Channel).
 */
@SuppressWarnings("unused")
public final class MembersVideoFilter extends Filter {

    private static final ByteTrieSearch membersBufferSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(
                    // InnerTube style constants (language-independent)
                    "BADGE_STYLE_TYPE_MEMBERS_ONLY",
                    "BADGE_STYLE_TYPE_MEMBERS_FIRST",
                    "badge-style-type-members-only",
                    "badge-style-type-members-first",
                    "MEMBERS_ONLY",
                    "MEMBERS_FIRST",

                    // Membership badge icons
                    "yt_outline_sponsor_stars",
                    "yt_outline_membership",
                    "yt_fill_membership",

                    // Multi-language text indicators in protobuf buffer
                    "Members only",
                    "Members-only",
                    "Members first",
                    "Members-first",
                    "メンバー限定",
                    "メンバー先行"
            )
    );

    private final StringFilterGroup videoCards;

    public MembersVideoFilter() {
        videoCards = new StringFilterGroup(
                Settings.HIDE_MEMBERS_VIDEOS,
                // Feed and search video cards
                "home_video_with_context.e",
                "video_with_context.e",
                "search_video_with_context.e",
                // Channel tab / tablet / list video lockups
                "compact_video.e",
                "video_lockup_with_attachment.e",
                // Shelf cards and related items
                "video_card.e",
                "related_video_with_context.e",
                "inline_shorts",
                "shorts_video_cell"
        );

        addPathCallbacks(videoCards);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface, String identifier, String accessibility,
                              String path, byte[] buffer, BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup, FilterContentType contentType, int contentIndex) {
        if (!Settings.HIDE_MEMBERS_VIDEOS.get()) {
            return false;
        }

        if (buffer != null && buffer.length > 0 && membersBufferSearch.matches(buffer)) {
            Logger.printDebug(() -> "MembersVideoFilter: Filtered members video (buffer match): " + path);
            return true;
        }

        if (accessibility != null && !accessibility.isEmpty()) {
            if (accessibility.contains("Members only")
                    || accessibility.contains("Members-only")
                    || accessibility.contains("Members first")
                    || accessibility.contains("Members-first")
                    || accessibility.contains("メンバー限定")
                    || accessibility.contains("メンバー先行")) {
                Logger.printDebug(() -> "MembersVideoFilter: Filtered members video (accessibility match): " + path);
                return true;
            }
        }

        return false;
    }
}
