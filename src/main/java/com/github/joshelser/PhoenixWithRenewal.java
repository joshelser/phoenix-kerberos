/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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
package com.github.joshelser;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

 /**
  * The ticket's lifetime is 10 minutes with a 15 minute renewal lifetime.
  * The expectation is that after the 2nd query, we'll expire the 10min lifetime and rely on
  * the renewal thread to acquire a new ticket. If we continuously interact with Phoenix,
  * the HBase RPC layer will automatically renew the ticket for us. So, if we query Phoenix
  * infrequently enough, we'll ultimately be forcing the thread to perform the renewal.
  */
public class PhoenixWithRenewal {
  private static final Logger LOG = LoggerFactory.getLogger(PhoenixWithRenewal.class);

  // Issue a query every 6 minutes
  private static final long QUERY_PERIOD = 6L * 60L * 1_000L;
  // Attempt a renewal every 30 seconds
  private static final long RENEWAL_PERIOD = 30_000;

  private static final String TABLE_NAME = "KERBEROS_TEST";
  private static final long NUM_ROWS = 100_000;
  private static final long UPDATES_PER_BATCH = 5_000;

  public static void main(String[] args) throws Exception {
    UserGroupInformation.loginUserFromKeytab("renewal1", "/usr/local/lib/hadoop/etc/secure/keytabs/renewal1.headless.keytab");
    final UserGroupInformation currentUser = UserGroupInformation.getCurrentUser();

    Thread t = spawnRenewalThread(currentUser);

    try {
      runQueries();
    } finally {
      t.interrupt();
      t.join(1000);
    }
  }

  private static void runQueries() throws Exception {
    try (Connection conn = DriverManager.getConnection("jdbc:phoenix:hw10447.local:2181:/hbase-1122630-secure");
        Statement stmt = conn.createStatement()) {
      // Create a table
      stmt.execute("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + "(pk varchar not null primary key, col1 integer)");
      // Write some data
      conn.setAutoCommit(false);
      try (PreparedStatement pstmt = conn.prepareStatement("UPSERT INTO " + TABLE_NAME + " VALUES(?,?)")) {
        for (long i = 0L; i < NUM_ROWS; i++) {
          pstmt.setString(1, Long.toString(i));
          pstmt.setLong(2, i);
          pstmt.executeUpdate();
          if (i % UPDATES_PER_BATCH == 0) {
            conn.commit();
          }
        }
        conn.commit();
      }
      conn.setAutoCommit(true);
      // Read the data
      while (true) {
        LOG.debug("Starting query");
        ResultSet results = stmt.executeQuery("SELECT * FROM " + TABLE_NAME + " LIMIT 1");
        if (results.next()) {
          if (!"0".equals(results.getString(1))) {
            LOG.warn("Found unexpected string column data: " + results.getString(1));
          }
          if (0L != results.getLong(2)) {
            LOG.warn("Found unexpected numeric column data: " + results.getLong(2));
          }
          if (results.next()) {
            LOG.warn("Found more than one row of data!");
          }
        } else {
          LOG.warn("Expected results for query, but found none");
        }
        results.close();

        LOG.debug("Query successfully completed");
        try {
          Thread.sleep(QUERY_PERIOD);
        } catch (InterruptedException e) {
          LOG.error("Query thread caught exception while sleeping. Exiting...", e);
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private static Thread spawnRenewalThread(final UserGroupInformation userToRenew) {
    Thread t = new Thread(new Runnable() {
      @Override public void run() {
        while (true) {
          LOG.debug("Invoking renewal");
          try {
            userToRenew.checkTGTAndReloginFromKeytab();
          } catch (IOException e) {
            LOG.error("Failed to renew ticket from keytab, will retry", e);
          }
          LOG.debug("Renewal completed");
          try {
            Thread.sleep(RENEWAL_PERIOD);
          } catch (InterruptedException e) {
            LOG.info("Renewal thread caught exception while sleeping. Exiting...");
            Thread.currentThread().interrupt();
            return;
          }
        }
      }
    });
    t.setDaemon(true);
    t.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
      @Override public void uncaughtException(Thread t, Throwable e) {
        LOG.error("Uncaught exception by thread", e);
      }
    });
    t.setName("Phoenix-KerberosCredentials-Renewal");
    t.start();
    return t;
  }
}
