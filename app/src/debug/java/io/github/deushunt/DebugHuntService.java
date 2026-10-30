package io.github.deushunt;

import io.github.deusble.android.ScanBackend;
import io.github.deusble.core.Observation;

/** Instrumentation fixture, excluded from release source sets. */
public final class DebugHuntService extends HuntService {
 public static volatile DebugHuntService instance;
 public static final Backend backend=new Backend();
 @Override public void onCreate(){super.onCreate();instance=this;}
 @Override protected ScanBackend createBackend(){return backend;}
 @Override public void onDestroy(){instance=null;super.onDestroy();}
 public static final class Backend implements ScanBackend {
  volatile Callback callback;
  public Availability availability(){return Availability.READY;}
  public void start(Callback next){callback=next;}
  public void stop(Callback previous){if(callback==previous)callback=null;}
  public void emit(Observation observation){Callback c=callback;if(c!=null)c.onAdvertisement(observation,true);}
 }
}
