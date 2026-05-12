package mil.army.usace.hec.dss.migrator;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reflection handles for {@code hec.heclib.*} classes loaded inside the isolated CL. */
final class HecDssHandles {

    final Constructor<?> utilitiesCtor;
    final Method setDSSFileName;
    final Method getDssFileVersion;
    final Method getNumberRecords;
    final Method convertVersion;
    final Method closeDSSFile;
    final Method getCatalog;
    final Method recordType;
    final Method dssFileName;

    final Constructor<?> griddedDataCtor;
    final Method gdSetDSSFileName;
    final Method gdSetPathname;
    final Method gdGetGridStructVersion;

    final Constructor<?> managerCtor;
    final Method managerOpen;
    final Method managerClose;

    final Method zquery;
    final Constructor<?> stringContainerCtor;
    final Field stringField;

    HecDssHandles(IsolatedClassLoader cl) throws ReflectiveOperationException {
        Class<?> utilitiesClass = cl.loadClass("hec.heclib.dss.HecDSSUtilities");
        utilitiesCtor = utilitiesClass.getConstructor();
        setDSSFileName = utilitiesClass.getMethod("setDSSFileName", String.class);
        getDssFileVersion = utilitiesClass.getMethod("getDssFileVersion");
        getNumberRecords = utilitiesClass.getMethod("getNumberRecords");
        convertVersion = utilitiesClass.getMethod("convertVersion", String.class);
        closeDSSFile = utilitiesClass.getMethod("closeDSSFile");
        getCatalog = utilitiesClass.getMethod("getCatalog", boolean.class, String.class);
        recordType = utilitiesClass.getMethod("recordType", String.class);
        dssFileName = utilitiesClass.getMethod("DSSFileName");

        Class<?> griddedDataClass = cl.loadClass("hec.heclib.grid.GriddedData");
        griddedDataCtor = griddedDataClass.getConstructor();
        gdSetDSSFileName = griddedDataClass.getMethod("setDSSFileName", String.class);
        gdSetPathname = griddedDataClass.getMethod("setPathname", String.class);
        gdGetGridStructVersion = griddedDataClass.getMethod("getGridStructVersion");

        Class<?> managerClass = cl.loadClass("hec.heclib.dss.HecDataManager");
        managerCtor = managerClass.getConstructor(String.class);
        managerOpen = managerClass.getMethod("open");
        managerClose = managerClass.getMethod("close");

        Class<?> heclibClass = cl.loadClass("hec.heclib.util.Heclib");
        Class<?> scClass = cl.loadClass("hec.heclib.util.stringContainer");
        zquery = heclibClass.getMethod("zquery", String.class, scClass, int[].class);
        stringContainerCtor = scClass.getConstructor();
        stringField = scClass.getField("string");
    }

    /** Op invoked against a managed {@code utilities} handle that is auto-closed afterward. */
    @FunctionalInterface
    interface UtilitiesOp<T> {
        T run(Object utilities) throws Exception;
    }

    /**
     * Converts {@code src} to {@code dst} and closes both. The trailing
     * setName/close on {@code dst} releases the native handle the converter
     * leaves open, otherwise Windows refuses to move {@code dst} afterward.
     */
    int convertAndClose(Object utilities, String src, String dst) throws Exception {
        setDSSFileName.invoke(utilities, src);
        int status = (int) convertVersion.invoke(utilities, dst);
        closeDSSFile.invoke(utilities);
        setDSSFileName.invoke(utilities, dst);
        closeDSSFile.invoke(utilities);
        return status;
    }

    /** Opens a utilities handle pointed at {@code filename}, reads the DSS version, and closes. */
    int peekVersion(String filename) throws Exception {
        Object utilities = utilitiesCtor.newInstance();
        try {
            setDSSFileName.invoke(utilities, filename);
            return (int) getDssFileVersion.invoke(utilities);
        } finally {
            closeQuietly(utilities);
        }
    }

    /**
     * Opens a utilities handle pointed at {@code filename}, runs {@code op}, and
     * closes the handle in a finally block. The op may close the handle itself
     * (e.g. via {@link #convertAndClose}); the trailing close is swallowed.
     */
    <T> T withUtilities(String filename, UtilitiesOp<T> op) throws Exception {
        Object utilities = utilitiesCtor.newInstance();
        try {
            setDSSFileName.invoke(utilities, filename);
            return op.run(utilities);
        } finally {
            closeQuietly(utilities);
        }
    }

    private void closeQuietly(Object utilities) {
        try { closeDSSFile.invoke(utilities); } catch (Exception ignore) {}
    }
}
