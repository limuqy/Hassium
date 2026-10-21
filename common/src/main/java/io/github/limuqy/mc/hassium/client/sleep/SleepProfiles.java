package io.github.limuqy.mc.hassium.client.sleep;

import io.github.limuqy.mc.hassium.Constants;
import io.github.limuqy.mc.hassium.platform.Services;
import net.minecraft.client.Minecraft;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A per-server/player preference, never a server-authoritative health value. */
public final class SleepProfiles {
    public static final int DEFAULT_AUTO_SECONDS=180;
    private SleepProfiles() {}
    private static Path path() { return Services.PLATFORM.getConfigDirectory().resolve("hassium/sleep-profiles.properties"); }
    private static String key() {
        Minecraft mc=Minecraft.getInstance();
        var server=mc.getCurrentServer();
        try {
            String identity=(server==null?"":server.ip.trim().toLowerCase(Locale.ROOT))+"/"+mc.getUser().getProfileId();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Properties load() {
        Properties properties=new Properties();
        if(Files.isRegularFile(path())) try(var reader=Files.newBufferedReader(path(),StandardCharsets.UTF_8)) { properties.load(reader); }
        catch(Exception e) { Constants.LOG.warn("Cannot read sleep preferences",e); }
        return properties;
    }
    public static float threshold() {
        try { float f=Float.parseFloat(load().getProperty(key(),"6")); return Float.isFinite(f)&&f>=0?f:6; }
        catch(NumberFormatException ignored) { return 6; }
    }
    public static void save(float threshold) {
        Properties properties=load(); properties.setProperty(key(),Float.toString(threshold));
        write(properties);
    }
    public static int autoSeconds() {
        try {
            int value=Integer.parseInt(load().getProperty(key()+".autoSeconds",Integer.toString(DEFAULT_AUTO_SECONDS)));
            return value==0 || value>=60 && value<=3600 ? value : DEFAULT_AUTO_SECONDS;
        } catch(NumberFormatException ignored) { return DEFAULT_AUTO_SECONDS; }
    }
    public static void save(float threshold,int autoSeconds) {
        if(autoSeconds!=0 && (autoSeconds<60 || autoSeconds>3600)) throw new IllegalArgumentException("Invalid auto sleep delay");
        Properties properties=load(); properties.setProperty(key(),Float.toString(threshold));
        properties.setProperty(key()+".autoSeconds",Integer.toString(autoSeconds));
        write(properties);
    }
    private static void write(Properties properties) {
        try {
            Files.createDirectories(path().getParent());
            Path tmp=path().resolveSibling("sleep-profiles.properties.tmp");
            try(var writer=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)) { properties.store(writer,"Hassium per-server/player sleep preferences"); }
            try { Files.move(tmp,path(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(tmp,path(),StandardCopyOption.REPLACE_EXISTING); }
        } catch(Exception e) { Constants.LOG.warn("Cannot save sleep preferences",e); }
        SleepClient.reloadPreferences();
    }
}
