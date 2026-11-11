package com.fader.tv;

import java.io.IOException;

/** Common browsing+streaming contract shared by the SMB client and the local/USB client. */
interface LibraryClient extends Stream {
    String rootUrl();
    String modeLabel();
    NasClient.Library quickLibrary(String url, String name) throws IOException;
    NasClient.Library indexLibrary(String url, String name, NasClient.Cancel cancel, NasClient.Progress progress, NasClient.Partial partial) throws IOException;
    int countAudio(String url, NasClient.Cancel cancel, NasClient.Progress progress) throws IOException;
}
