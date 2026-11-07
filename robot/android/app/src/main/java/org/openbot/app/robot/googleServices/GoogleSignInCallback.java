package org.openbot.app.robot.googleServices;

import com.google.firebase.auth.FirebaseUser;

/** Callbacks for Google sign-in state changes, delivered by {@link GoogleServices}. */
public interface GoogleSignInCallback {
  void onSignInSuccess(FirebaseUser account);

  void onSignInFailed(Exception exception);

  void onSignOutSuccess();

  void onSignOutFailed(Exception exception);
}
