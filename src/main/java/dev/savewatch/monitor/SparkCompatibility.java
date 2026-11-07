package dev.savewatch.monitor;

/**
 * Optional, dependency-free Spark presence check.
 * Spark's public integration surface differs by platform/version; this class deliberately
 * avoids linking against Spark internals so SaveWatch remains loadable without Spark.
 */
public final class SparkCompatibility {
    private SparkCompatibility() {}

    public static boolean isPresent() {
        try {
            Class.forName("me.lucko.spark.common.SparkPlatform", false,
                    SparkCompatibility.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static String status() {
        return isPresent() ? "detected; API bridge not activated" : "not installed";
    }
}
