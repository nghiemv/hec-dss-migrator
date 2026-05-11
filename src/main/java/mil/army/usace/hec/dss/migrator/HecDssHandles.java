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
}
