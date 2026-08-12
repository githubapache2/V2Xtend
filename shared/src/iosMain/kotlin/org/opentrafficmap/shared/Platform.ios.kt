package org.opentrafficmap.shared

import platform.UIKit.UIDevice

internal actual object Platform {
    actual val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}
