package com.nemesis.batterywatchdog;

/**
 * Delete all data: the one command that removes files, kept apart so it can be
 * tested off the car (tests/test_wipe.sh). Plain Java, no Android classes.
 *
 * It may only ever touch:
 *  - the contents of the monitor's own state folder, and only when that folder is
 *    exactly the expected one on the disk: no symlink in it or on the way to it
 *    (checked with `cd -P` + `pwd -P`); otherwise nothing there is deleted;
 *  - the Import history copy folder, under the same check (a symlink in its place
 *    is removed itself, never followed);
 *  - the monitor's log, emptied only if it's a plain file (not a symlink).
 * rm -r never follows symlinks inside a folder (it removes the link), and every
 * name is given as ./name so none can be read as an option.
 */
final class Wipe {
    private Wipe() {}

    static final String NEM_STATE = "/data/nemesis/state/battery.watchdog";
    static final String HANDOVER = "/data/local/tmp/battery.watchdog-history";
    static final String NEM_LOG = "/data/nemesis/logs/battery.watchdog/watch.log";

    /** The only state folders Delete all data accepts: the NEMESIS package's, or the HMG-API mod service's. */
    static boolean allowedState(String s) {
        return s != null && (NEM_STATE.equals(s) || s.matches("/data/hmgapi/run/[a-z0-9][a-z0-9._-]{0,63}/watch"));
    }

    static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /**
     * The shell command. Prints nothing when everything is gone; otherwise what was
     * skipped or left. {@code log} may be null (nothing to empty).
     */
    static String command(String state, String handover, String log) {
        String s = q(state), h = q(handover);
        StringBuilder b = new StringBuilder();
        b.append("if cd -P ").append(s).append(" 2>/dev/null && [ \"$(pwd -P)\" = ").append(s).append(" ]; then ")
         .append("rm -rf ./* ./.[!.]* ./..?* 2>&1; ")
         .append("else [ -e ").append(s).append(" ] && echo 'skipped '").append(s).append("': not the expected folder'; fi; ");
        b.append("cd /; ");
        b.append("if [ -L ").append(h).append(" ]; then rm -f ").append(h).append("; ")
         .append("elif [ -d ").append(h).append(" ]; then ")
         .append("if cd -P ").append(h).append(" 2>/dev/null && [ \"$(pwd -P)\" = ").append(h).append(" ]; then cd /; rm -rf ").append(h).append(" 2>&1; ")
         .append("else echo 'skipped '").append(h).append("': not the expected folder'; fi; fi; ");
        b.append("cd /; ");
        if (log != null) {
            String l = q(log);
            b.append("if [ -f ").append(l).append(" ] && [ ! -L ").append(l).append(" ]; then : > ").append(l).append("; fi; ");
        }
        b.append("_left=$(ls -A ").append(s).append(" 2>/dev/null); [ -n \"$_left\" ] && echo 'left in '").append(s).append("': '$_left; ")
         .append("[ -e ").append(h).append(" ] && echo 'left: '").append(h).append("; true");
        return b.toString();
    }

    /** for tests: print the command for the given paths */
    public static void main(String[] a) {
        System.out.print(command(a[0], a[1], a.length > 2 ? a[2] : null));
    }
}
