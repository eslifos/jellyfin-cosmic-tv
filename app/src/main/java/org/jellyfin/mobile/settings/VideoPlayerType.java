package org.jellyfin.mobile.settings;


import androidx.annotation.StringDef;

@StringDef({
    VideoPlayerType.WEB_PLAYER,
    VideoPlayerType.WEB_PLAYER_NATIVE_FALLBACK,
    VideoPlayerType.EXO_PLAYER,
    VideoPlayerType.EXTERNAL_PLAYER
})
public @interface VideoPlayerType {
    String WEB_PLAYER = "webui";
    /** The web player, and the integrated one for the videos it could only play transcoded (the web decides). */
    String WEB_PLAYER_NATIVE_FALLBACK = "webui-native-fallback";
    String EXO_PLAYER = "exoplayer";
    String EXTERNAL_PLAYER = "external";
}
