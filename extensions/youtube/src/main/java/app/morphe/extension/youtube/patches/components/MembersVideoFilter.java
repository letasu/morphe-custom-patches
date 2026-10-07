package app.morphe.extension.youtube.patches.components;

import androidx.annotation.Nullable;

import java.util.Locale;

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
                    "BADGE_STYLE_TYPE_MEMBERSHIP",
                    "badge-style-type-members-only",
                    "badge-style-type-members-first",
                    "badge-style-type-membership",
                    "MEMBERS_ONLY",
                    "MEMBERS_FIRST",
                    "MEMBERSHIP",
                    "members_only",
                    "members_first",
                    "members-only",
                    "members-first",

                    // Membership badge icons & protobuf tags
                    "yt_outline_sponsor_stars",
                    "yt_fill_sponsor_stars",
                    "yt_outline_membership",
                    "yt_fill_membership",
                    "sponsor_stars",
                    "sponsorships",
                    "SPONSORSHIPS",
                    "sponsors_only",
                    "SPONSORS_ONLY",

                    // Multi-language text indicators in protobuf buffer
                    "Members only",
                    "Members-only",
                    "Members first",
                    "Members-first",
                    "Member only",
                    "Member-only",
                    "Member first",
                    "Member-first",
                    "メンバー限定",
                    "メンバー先行",
                    "メンバーシップ",
                    "メンバー専用"
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
                "grid_video.e",
                "inline_shorts",
                "shorts_video_cell",
                "shorts_lockup_cell.e",
                "shorts_pivot_item.e"
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

        if (asciiStrings != null) {
            String ascii = asciiStrings.getStrings();
            if (ascii.contains("BADGE_STYLE_TYPE_MEMBERS_ONLY")
                    || ascii.contains("BADGE_STYLE_TYPE_MEMBERS_FIRST")
                    || ascii.contains("BADGE_STYLE_TYPE_MEMBERSHIP")
                    || ascii.contains("badge-style-type-members-only")
                    || ascii.contains("badge-style-type-members-first")
                    || ascii.contains("yt_outline_sponsor_stars")
                    || ascii.contains("yt_fill_sponsor_stars")
                    || ascii.contains("yt_outline_membership")
                    || ascii.contains("yt_fill_membership")
                    || ascii.contains("sponsorships")
                    || ascii.contains("sponsor_stars")
                    || ascii.contains("Members only")
                    || ascii.contains("Members-only")
                    || ascii.contains("Members first")
                    || ascii.contains("Members-first")) {
                Logger.printDebug(() -> "MembersVideoFilter: Filtered members video (ascii match): " + path);
                return true;
            }
        }

        if (accessibility != null && !accessibility.isEmpty()) {
            String lower = accessibility.toLowerCase(Locale.ROOT);
            if (lower.contains("members only")
                    || lower.contains("members-only")
                    || lower.contains("members first")
                    || lower.contains("members-first")
                    || lower.contains("member only")
                    || lower.contains("member first")
                    || accessibility.contains("メンバー限定")
                    || accessibility.contains("メンバー先行")
                    || accessibility.contains("メンバー専用")
                    || accessibility.contains("メンバーシップ")) {
                Logger.printDebug(() -> "MembersVideoFilter: Filtered members video (accessibility match): " + path);
                return true;
            }
        }

        return false;
    }
}
