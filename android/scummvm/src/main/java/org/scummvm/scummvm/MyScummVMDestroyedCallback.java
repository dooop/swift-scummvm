package org.scummvm.scummvm;

/**
 * Wrapper-owned replacement for the identically named interface upstream
 * declares at the bottom of {@code ScummVMActivity.java}.
 *
 * <p>{@code ScummVM.java}'s constructor takes this type, but
 * {@code ScummVMActivity} itself is not vendored into this library (it pulls in
 * upstream's launcher UI and {@code res/} folder), so the declaration has to be
 * supplied here. Keep it signature-compatible with upstream.
 */
public interface MyScummVMDestroyedCallback {
	void handle(int exitResult);
}
