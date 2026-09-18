package com.github.dtretyakov.monkeyc.run

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.IdeFrame

/**
 * Looks for attached watches when the IDE comes back to the foreground.
 *
 * There is no portable way to be told that a USB device arrived — `mtp-rs` answers a question and
 * does not raise events, and a watch that mounts as a disk only shows up as a new filesystem root.
 * Polling was the other option and it is the wrong one: a subprocess every few seconds for as long
 * as a project is open, to answer a question nobody is asking.
 *
 * Coming back to the IDE window is the one moment that reliably follows plugging something in. So
 * that is when it looks, and by the time the device chip is opened the answer is already there.
 * Without this the chip rendered from the previous look, and the first open after attaching a watch
 * showed the state before it — which reads exactly like a plugin that only reads the bus at
 * startup.
 *
 * [GarminTarget.refreshAttached] is itself rate-limited, so alt-tabbing costs at most one look
 * every couple of seconds, and nothing at all when a look has just happened.
 */
class AttachedWatchWatcher : ApplicationActivationListener {

    override fun applicationActivated(ideFrame: IdeFrame) {
        // Off the UI thread: this walks the mount points and starts a subprocess, and it is
        // reacting to a window gaining focus.
        ApplicationManager.getApplication().executeOnPooledThread { GarminTarget.refreshAttached() }
    }
}
