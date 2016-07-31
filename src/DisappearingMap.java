/**
 * Created by Александр on 11.07.2016.
 */

import com.sun.org.apache.xpath.internal.SourceTree;
import com.sun.xml.internal.ws.encoding.soap.SOAP12Constants;

import java.awt.*;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class DisappearingMap<K,V> implements Map<K,V> {

    public static final int defaultLifetime = 60; //Lifetime of every map entry
    public static final int defaultExpirationTime = 1; //Frequency of checking the map
    private static volatile int expirerCount = 1; //Amount of threads dealing with expiration

    private final ConcurrentHashMap<K, DisappearingObject> storage; //Map for storing data
    private final Expirer expirer; //Object monitoring the map

    public DisappearingMap() {
        this(defaultLifetime, defaultExpirationTime);
    }

    public DisappearingMap(int timeOfLiving) {
        this(timeOfLiving, defaultExpirationTime);
    }

    public DisappearingMap(int timeOfLiving, int expirationTime) {
        this(new ConcurrentHashMap<K, DisappearingObject>(),
                timeOfLiving, expirationTime);
    }

    private DisappearingMap(ConcurrentHashMap<K, DisappearingObject> storage,
                            int timeOfLiving, int expirationTime) {
        this.storage = storage;
        this.expirer = new Expirer();
        expirer.setTimeOfLiving(timeOfLiving);
        expirer.setExpirationTime(expirationTime);
    }


    public void clear() {
        storage.clear();
    }

    public boolean containsKey(Object key) {
        return storage.containsKey(key);
    }

    public boolean containsValue(Object value) {
        return storage.containsValue(value);
    }

    public Set<Map.Entry<K, V>> entrySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean equals(Object o) {
        return storage.equals(o);
    }

    public V get(Object key) {
        DisappearingObject object = storage.get(key);
        if (object != null) {
            object.setLastAccessTime(System.currentTimeMillis());
            return object.getValue();
        }
        return null;
    }

    @Override
    public int hashCode() {
        return storage.hashCode();
    }

    public boolean isEmpty() {
        return storage.isEmpty();
    }

    public Set<K> keySet() {
        return storage.keySet();
    }

    public V put(K key, V value) {
        DisappearingObject result = storage.put(key,
                new DisappearingObject(key, value, System.currentTimeMillis()));
        if (result == null) {
            return null;
        }
        return result.getValue();
    }

    public void putAll(Map<? extends K, ? extends V> map) {
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
            this.put(entry.getKey(), entry.getValue());
        }
    }

    public V remove(Object key) {
        DisappearingObject result = storage.remove(key);
        if (result == null) {
            return null;
        }
        return result.getValue();
    }

    public int size() {
        return storage.size();
    }

    public Collection<V> values() {
        throw new UnsupportedOperationException();
    }

    public Expirer getExpirer() {
        return expirer;
    }

    public int getExpirationTime() {
        return expirer.getExpirationTime();
    }

    public int getTimeOfLiving() {
        return expirer.getTimeOfLiving();
    }

    public void setExpirationTime(int expirationTime) {
        expirer.setExpirationTime(expirationTime);
    }

    public void setTimeOfLiving(int timeOfLiving) {
        expirer.setTimeOfLiving(timeOfLiving);
    }

    private class DisappearingObject {
        private K key;
        private V value;
        private long lastAccessTime;
        private final ReadWriteLock lock = new ReentrantReadWriteLock();

        DisappearingObject(K key, V value, long lastAccessTime) {
            if (value == null) {
                throw new IllegalArgumentException(
                        "Object can not be null.");
            }
            this.key = key;
            this.value = value;
            this.lastAccessTime = lastAccessTime;
        }

        public long getLastAccessTime() {
            lock.readLock().lock();
            try {
                return lastAccessTime;
            } finally {
                lock.readLock().unlock();
            }
        }

        public void setLastAccessTime(long lastAccessTime) {
            lock.writeLock().lock();
            try {
                this.lastAccessTime = lastAccessTime;
            } finally {
                lock.writeLock().unlock();
            }
        }

        public K getKey() {
            return key;
        }

        public V getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            return value.equals(o);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    public class Expirer implements Runnable {
        private final ReadWriteLock stateLock = new ReentrantReadWriteLock(); //Lock for concurrency
        private long timeOfLivingMillis; //Entry lifetime
        private long expirationTimeMillis; //Frequency of monitoring the map
        private boolean running = false; //State of the monitoring thread
        private final Thread expirerThread; //Thread for monitoring the map

        public Expirer() {
            expirerThread = new Thread(this, "DisappearingMapExpirer - " + expirerCount++);
            expirerThread.setDaemon(true);
        }

        public void run() {
            while (running) {
                processExpires();
                try {
                    Thread.sleep(expirationTimeMillis);
                } catch (InterruptedException e) {

                }
            }
        }

        //This method deletes the entries based on time of living
        private void processExpires() {
            long time = System.currentTimeMillis();
            for (DisappearingObject o : storage.values()) {
                if (timeOfLivingMillis <= 0) {
                    continue;
                }

                long timeIdle = time - o.getLastAccessTime();
                if (timeIdle >= timeOfLivingMillis) {
                    storage.remove(o.getKey());
                }
            }
        }

        //This method is called to start the thread
        public void startExpiring() {
            stateLock.writeLock().lock();
            try {
                if(!running) {
                    running = true;
                    expirerThread.start();
                }
            } finally {
                stateLock.writeLock().unlock();
            }
        }

        public void startExpiringIfNotStarted() {
            stateLock.readLock().lock();
            try {
                if(running) {
                    return;
                }
            } finally {
                stateLock.writeLock().unlock();
            }
        }

        //This method needs to be called to stop monitoring the map
        public void stopExpiring() {
            stateLock.writeLock().lock();
            try {
                if(running) {
                    running = false;
                    expirerThread.interrupt();
                }
            } finally {
                stateLock.writeLock().unlock();
            }
        }

        public boolean isRunning() {
            stateLock.readLock().lock();
            try {
                return running;
            } finally {
                stateLock.readLock().unlock();
            }
        }

        public int getTimeOfLiving() {
            stateLock.readLock().lock();
            try {
                return (int) timeOfLivingMillis / 1000;
            } finally {
                stateLock.readLock().unlock();
            }
        }

        public void setTimeOfLiving(long timeOfLiving) {
            stateLock.writeLock().lock();
            try {
                this.timeOfLivingMillis = timeOfLiving * 1000;
            } finally {
                stateLock.writeLock().unlock();
            }
        }

        public int getExpirationTime() {
            stateLock.readLock().lock();
            try {
                return (int) expirationTimeMillis / 1000;
            } finally {
                stateLock.readLock().unlock();
            }
        }

        public void setExpirationTime(long expirationTime) {
            stateLock.writeLock().lock();
            try {
                this.expirationTimeMillis = expirationTime * 1000;
            } finally {
                stateLock.writeLock().unlock();
            }
        }
    }

    public static void main(String[] args) {
        try {
            DisappearingMap<String, String> dmap = new DisappearingMap<>(15, 1);

            dmap.put("1", "one");
            dmap.put("2", "two");

            DisappearingMap.Expirer expirer = dmap.getExpirer();

            expirer.startExpiring();

            Iterator iter = dmap.keySet().iterator();
            while (iter.hasNext()) {
                System.out.println("item key: " + iter.next());
            }

            Thread.sleep(10000);

            dmap.put("3", "three");
            dmap.put("4", "four");

            Iterator iter2 = dmap.keySet().iterator();
            while (iter2.hasNext()) {
                System.out.println("item key: " + iter2.next());
            }
            Thread.sleep(10000);

            dmap.put("5","five");
            dmap.put("6","six");

            expirer.stopExpiring();

            Iterator iter3 = dmap.keySet().iterator();
            while (iter3.hasNext()) {
                System.out.println("item key: " + iter3.next());
            }
        } catch (InterruptedException ie) {
        }
    }
}





