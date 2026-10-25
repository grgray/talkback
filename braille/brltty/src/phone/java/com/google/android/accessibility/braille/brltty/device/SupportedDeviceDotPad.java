package com.google.android.accessibility.braille.brltty.device;

import android.util.Pair;
import com.google.android.accessibility.braille.brltty.KeyNameMapBuilder;
import com.google.android.accessibility.braille.brltty.R;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Supported device info for Dot Pad (DotPad320X) braille displays. */
public class SupportedDeviceDotPad extends SupportedDevice {
  private static final ImmutableList<Pattern> NAME_REGEXES =
      ImmutableList.of(Pattern.compile("DotPad"));

  @Override
  public String driverCode() {
    return "dotpad";
  }

  @Override
  public boolean connectSecurely() {
    return false;
  }

  @Override
  public Map<String, Integer> friendlyKeyNames() {
    return new KeyNameMapBuilder()
        .add("PanLeft", R.string.key_pan_left)
        .add("PanRight", R.string.key_pan_right)
        .add("F1", R.string.key_F1)
        .add("F2", R.string.key_F2)
        .add("F3", R.string.key_F3)
        .add("F4", R.string.key_F4)
        .build();
  }

  @Override
  public Set<Pair<Integer, Integer>> vendorProdIds() {
    return ImmutableSet.of();
  }

  @Override
  public List<Pattern> nameRegexes() {
    return NAME_REGEXES;
  }
}
