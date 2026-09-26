package com.tylerabitbol.libra

actual fun platformName(): String = "JVM " + System.getProperty("java.version")
