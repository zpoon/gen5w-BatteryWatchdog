import com.nemesis.batterywatchdog.TxnResolver;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;

/** Usage: java ResolverTest <fixture.dex> */
public class ResolverTest {
    static void check(Map<String, Integer> m, String name, int want) {
        Integer got = m.get(name);
        if (got == null || got != want) throw new AssertionError(name + ": want " + want + ", got " + got + " in " + m);
    }
    public static void main(String[] a) throws Exception {
        byte[] dex = Files.readAllBytes(new File(a[0]).toPath());
        Map<String, Integer> m = TxnResolver.transactions(dex, TxnResolver.STUB);
        check(m, "getBatSoc", 137);
        check(m, "remainBatteryPercentage", 74);
        check(m, "getXEV_Bms_GDS_SOH", 214);
        check(m, "reqResetSignals", 132);
        check(m, "bigOne", 70000);
        // same dex wrapped in an ODEX ("dey\n") header
        byte[] odex = new byte[40 + dex.length];
        odex[0] = 'd'; odex[1] = 'e'; odex[2] = 'y'; odex[3] = '\n';
        odex[8] = 40; int n = dex.length;
        odex[12] = (byte) n; odex[13] = (byte) (n >> 8); odex[14] = (byte) (n >> 16); odex[15] = (byte) (n >> 24);
        System.arraycopy(dex, 0, odex, 40, dex.length);
        Map<String, Integer> m2 = TxnResolver.transactions(odex, TxnResolver.STUB);
        if (!m.equals(m2)) throw new AssertionError("odex result differs: " + m2);
        // a class that isn't there resolves to nothing
        if (!TxnResolver.transactions(dex, "Lno/Such;").isEmpty()) throw new AssertionError("phantom class");
        System.out.println("resolver ok: " + m);
    }
}
