package com.fader.tv;
public final class QueuePolicy {
 public static int next(int current, int count, boolean random, java.util.Random rng) {
  if(count<=1) return 0;
  return random ? (current+1+rng.nextInt(count-1))%count : (current+1)%count;
 }
 public static float fade(long elapsed, long duration) { return duration<=0 ? 1f : Math.max(0f,Math.min(1f,(float)elapsed/duration)); }
}
