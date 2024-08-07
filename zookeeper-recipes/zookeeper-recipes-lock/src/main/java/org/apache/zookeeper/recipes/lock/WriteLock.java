/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.recipes.lock;

import static org.apache.zookeeper.CreateMode.EPHEMERAL_SEQUENTIAL;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A <a href="package.html">protocol to implement an exclusive
 *  write lock or to elect a leader</a>.
 *
 *  <p>You invoke {@link #lock()} to start the process of grabbing the lock;
 *  you may get the lock then or it may be some time later.
 *
 *  <p>You can register a listener so that you are invoked when you get the lock;
 *  otherwise you can ask if you have the lock by calling {@link #isOwner()}.
 *
 */
public class WriteLock extends ProtocolSupport {

    private static final Logger LOG = LoggerFactory.getLogger(WriteLock.class);

    private final String dir;
    private String id;
    private ZNodeName idName;
    private String ownerId;
    private String lastChildId;
    private byte[] data = {0x12, 0x34};
    private LockListener callback;
    private LockZooKeeperOperation zop;

    /**
     * zookeeper constructor for writelock.
     *
     * @param zookeeper zookeeper client instance
     * @param dir the parent path you want to use for locking
     * @param acl the acls that you want to use for all the paths, if null world read/write is used.
     */
    public WriteLock(ZooKeeper zookeeper, String dir, List<ACL> acl) {
        super(zookeeper);
        this.dir = dir;
        if (acl != null) {
            setAcl(acl);
        }
        this.zop = new LockZooKeeperOperation();
    }

    /**
     * zookeeper constructor for writelock with callback.
     *
     * @param zookeeper the zookeeper client instance
     * @param dir the parent path you want to use for locking
     * @param acl the acls that you want to use for all the paths
     * @param callback the call back instance
     */
    public WriteLock(
        ZooKeeper zookeeper,
        String dir,
        List<ACL> acl,
        LockListener callback) {
        this(zookeeper, dir, acl);
        this.callback = callback;
    }

    /**
     * return the current locklistener.
     *
     * @return the locklistener
     */
    public synchronized LockListener getLockListener() {
        return this.callback;
    }

    /**
     * register a different call back listener.
     *
     * @param callback the call back instance
     */
    public synchronized void setLockListener(LockListener callback) {
        this.callback = callback;
    }

    /**
     * Removes the lock or associated znode if
     * you no longer require the lock. this also
     * removes your request in the queue for locking
     * in case you do not already hold the lock.
     *
     * @throws RuntimeException throws a runtime exception
     * if it cannot connect to zookeeper.
     */
    public synchronized void unlock() throws RuntimeException {

        if (!isClosed() && id != null) {
            // we don't need to retry this operation in the case of failure
            // as ZK will remove ephemeral files and we don't wanna hang
            // this process when closing if we cannot reconnect to ZK
            try {

                ZooKeeperOperation zopdel = () -> {
                    zookeeper.delete(id, -1);
                    return Boolean.TRUE;
                };
                zopdel.execute();
            } catch (InterruptedException e) {
                LOG.warn("Unexpected exception", e);
                // set that we have been interrupted.
                Thread.currentThread().interrupt();
            } catch (KeeperException.NoNodeException e) {
                // do nothing
            } catch (KeeperException e) {
                LOG.warn("Unexpected exception", e);
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                LockListener lockListener = getLockListener();
                if (lockListener != null) {
                    lockListener.lockReleased();
                }
                id = null;
            }
        }
    }

    /**
     * the watcher called on
     * getting watch while watching
     * my predecessor.
     */
    private class LockWatcher implements Watcher {

        public void process(WatchedEvent event) {
            // lets either become the leader or watch the new/updated node
            LOG.debug("Watcher fired: {}", event);
            try {
                lock();
            } catch (Exception e) {
                LOG.warn("Failed to acquire lock", e);
            }
        }

    }

    /**
     * a zookeeper operation that is mainly responsible
     * for all the magic required for locking.
     */
    private class LockZooKeeperOperation implements ZooKeeperOperation {

        /**
         * find if we have been created earlier if not create our node.
         *
         * @param prefix the prefix node
         * @param zookeeper the zookeeper client
         * @param dir the dir paretn
         * @throws KeeperException
         * @throws InterruptedException
         */
        private void findPrefixInChildren(String prefix, ZooKeeper zookeeper, String dir)
            throws KeeperException, InterruptedException {
            List<String> names = zookeeper.getChildren(dir, false);
            for (String name : names) {
                if (name.startsWith(prefix)) {
                    id = name;
                    LOG.debug("Found id created last time: {}", id);
                    break;
                }
            }
            if (id == null) {
                id = zookeeper.create(dir + "/" + prefix, data, getAcl(), EPHEMERAL_SEQUENTIAL);

                LOG.debug("Created id: {}", id);
            }

        }

        /**
         * the command that is run and retried for actually
         * obtaining the lock.
         *
         * @return if the command was successful or not
         */
        @SuppressFBWarnings(
            value = "NP_NULL_PARAM_DEREF_NONVIRTUAL",
            justification = "findPrefixInChildren will assign a value to this.id")
        public boolean execute() throws KeeperException, InterruptedException {
            do {
                if (id == null) {
                    long sessionId = zookeeper.getSessionId();
                    String prefix = "x-" + sessionId + "-";
                    // lets try look up the current ID if we failed
                    // in the middle of creating the znode
                    findPrefixInChildren(prefix, zookeeper, dir);
                    idName = new ZNodeName(id);
                }
                List<String> names = zookeeper.getChildren(dir, false);
                if (names.isEmpty()) {
                    LOG.warn("No children in: {} when we've just created one! Lets recreate it...", dir);
                    // lets force the recreation of the id
                    id = null;
                } else {
                    // lets sort them explicitly (though they do seem to come back in order usually :)
                    SortedSet<ZNodeName> sortedNames = new TreeSet<>();
                    for (String name : names) {
                        sortedNames.add(new ZNodeName(dir + "/" + name));
                    }
                    ownerId = sortedNames.first().getName();
                    SortedSet<ZNodeName> lessThanMe = sortedNames.headSet(idName);
                    if (!lessThanMe.isEmpty()) {
                        ZNodeName lastChildName = lessThanMe.last();
                        lastChildId = lastChildName.getName();
                        LOG.debug("Watching less than me node: {}", lastChildId);
                        Stat stat = zookeeper.exists(lastChildId, new LockWatcher());
                        if (stat != null) {
                            return Boolean.FALSE;
                        } else {
                            LOG.warn("Could not find the stats for less than me: {}", lastChildName.getName());
                        }
                    } else {
                        if (isOwner()) {
                            LockListener lockListener = getLockListener();
                            if (lockListener != null) {
                                lockListener.lockAcquired();
                            }
                            return Boolean.TRUE;
                        }
                    }
                }
            }
            while (id == null);
            return Boolean.FALSE;
        }

    }

    /**
     * Attempts to acquire the exclusive write lock returning whether or not it was
     * acquired. Note that the exclusive lock may be acquired some time later after
     * this method has been invoked due to the current lock owner going away.
     */
    public synchronized boolean lock() throws KeeperException, InterruptedException {
        if (isClosed()) {
            return false;
        }
        ensurePathExists(dir);

        return (Boolean) retryOperation(zop);
    }

    /**
     * return the parent dir for lock.
     *
     * @return the parent dir used for locks.
     */
    public String getDir() {
        return dir;
    }

    /**
     * Returns true if this node is the owner of the
     *  lock (or the leader).
     */
    public boolean isOwner() {
        return id != null && id.equals(ownerId);
    }

    /**
     * return the id for this lock.
     *
     * @return the id for this lock
     */
    public String getId() {
        return this.id;
    }

}

