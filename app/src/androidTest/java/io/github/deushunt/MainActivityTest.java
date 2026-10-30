package io.github.deushunt;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;

public class MainActivityTest {
  @Test
  public void launchesWithoutLocationPermissionOrDownloadedMap() {
    android.app.Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Intent intent = new Intent();
    intent.setClassName(i.getTargetContext(), "io.github.deushunt.MainActivity");
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    Activity a = i.startActivitySync(intent);
    assertNotNull(a.findViewById(android.R.id.content));
    i.runOnMainSync(a::finish);
  }
}
