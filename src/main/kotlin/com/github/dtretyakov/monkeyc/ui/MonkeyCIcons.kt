package com.github.dtretyakov.monkeyc.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object MonkeyCIcons {
    /** A watch with a hand, which is what a Connect IQ project ends up on. */
    @JvmField
    val CONNECT_IQ: Icon = IconLoader.getIcon("/icons/connectiq.svg", MonkeyCIcons::class.java)

    /** Monkey C source: the same watch, so a `.mc` file reads as the thing that runs on one. */
    @JvmField
    val MONKEY_C: Icon = IconLoader.getIcon("/icons/monkeyc.svg", MonkeyCIcons::class.java)

    /** A jungle file: the build's list of what goes into the app. */
    @JvmField
    val JUNGLE: Icon = IconLoader.getIcon("/icons/jungle.svg", MonkeyCIcons::class.java)

    /** A style sheet: a block of properties. */
    @JvmField
    val MSS: Icon = IconLoader.getIcon("/icons/mss.svg", MonkeyCIcons::class.java)
}
