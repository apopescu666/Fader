package com.fader.mobil.core;

import java.io.IOException;

/** Common browsing+streaming contract shared by the SMB client and the local/USB client. */
interface LibraryClient extends Stream {
    String rootUrl();
    String modeLabel();
    Library quickLibrary(String url, String name) throws IOException;
    Library indexLibrary(String url, String name, Media.Cancel cancel, Media.Progress progress, Media.Partial partial) throws IOException;
    int countAudio(String url, Media.Cancel cancel, Media.Progress progress) throws IOException;
}
