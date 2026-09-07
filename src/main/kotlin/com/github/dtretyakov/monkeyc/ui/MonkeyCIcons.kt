package com.github.dtretyakov.monkeyc.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object MonkeyCIcons {
    /** A watch with a hand, which is what a Connect IQ project ends up on. */
    @JvmField
    val CONNECT_IQ: Icon = IconLoader.getIcon("/icons/connectiq.svg", MonkeyCIcons::class.java)
}
