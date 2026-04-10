package stub.sun.misc;

import java.lang.reflect.Field;

public class Unsafe {
    public int arrayBaseOffset(Class<?> arrayClass) {
        return 0;
    }

    public int arrayIndexScale(Class<?> arrayClass) {
        return 0;
    }

    public long getLong(Object obj, long offset) {
        return 0;
    }

    public void putLong(Object obj, long offset, long newValue) {
    }

    public long objectFieldOffset(Field field) {
        return 0;
    }

    public int getInt(Object obj, long offset) {
        return 0;
    }

    public void putInt(Object obj, long offset, int newValue) {
    }
}
