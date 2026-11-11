#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p tests/out
javac -d tests/out app/src/main/java/com/fader/tv/QueuePolicy.java tests/QueuePolicyTest.java
java -cp tests/out QueuePolicyTest

javac -d tests/out app/src/main/java/com/fader/tv/DiscoveryPolicy.java tests/DiscoveryPolicyTest.java
java -cp tests/out com.fader.tv.DiscoveryPolicyTest

# 0.4 library/parsing policies: crossfade lengths, natural-order queue, tag-less metadata, DIDL-Lite.
javac -d tests/out \
  app/src/main/java/com/fader/tv/CrossfadePolicy.java \
  app/src/main/java/com/fader/tv/LibraryPolicy.java \
  app/src/main/java/com/fader/tv/MetaParser.java \
  app/src/main/java/com/fader/tv/DidlParser.java \
  tests/P04Test.java
java -cp tests/out com.fader.tv.P04Test

# 0.4 UPnP/DLNA string logic (no sockets): control-URL resolution, relative URLs, DIDL extraction.
javac -d tests/out app/src/main/java/com/fader/tv/UpnpPolicy.java tests/UpnpPolicyTest.java
java -cp tests/out com.fader.tv.UpnpPolicyTest
