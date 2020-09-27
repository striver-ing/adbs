# adbs
communicate with adb daemon

# demo
```java
DefaultAdbDevice device = SocketAdbDevice.connect("127.0.0.1", 5555);
device.shell("ls", "-l", "/sdcard").addListener(f -> {
    if (f.cause() != null) {
        f.cause().printStacktrace();
    } else{
        System.out.println((String)f.getNow());
    }
});
```
