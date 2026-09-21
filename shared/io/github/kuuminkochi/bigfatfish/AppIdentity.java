package io.github.kuuminkochi.bigfatfish;

public final class AppIdentity {
    public static final String APP_ID = "io.github.kuuminkochi.bigfatfish";
    public static final String AUTHORITY = APP_ID + ".control";
    public static final String SERVICE_CLASS = APP_ID + ".CompanionService";
    public static final String HELPER_PROCESS = "bigfatfish-helper";
    public static final String DESCRIPTOR = APP_ID + ".Observer.v1";
    public static final String PREFS = "bigfatfish";
    private AppIdentity() { }
}
