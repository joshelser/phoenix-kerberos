## Renewal of Keytab-based logins with Apache Phoenix

In `kadmin`:

```
addprinc -maxlife 5min -maxrenewlife 10min -randkey renewal1
xst -k /path/to/renewal1.headless.keytab renewal1
```

Ensure that the `krbtgt/REALM` realm has a non-zero maximum renewable lifetime:

```
kadmin.local:  getprinc krbtgt/EXAMPLE.COM
Principal: krbtgt/EXAMPLE.COM@EXAMPLE.COM
Expiration date: [never]
Last password change: [never]
Password expiration date: [none]
Maximum ticket life: 1 day 00:00:00
Maximum renewable life: 7 days 00:00:00
Last modified: Mon Dec 22 11:28:09 EST 2014 (nn/admin@EXAMPLE.COM)
Last successful authentication: [never]
Last failed authentication: [never]
Failed password attempts: 0
Number of keys: 4
Key: vno 1, aes256-cts-hmac-sha1-96
Key: vno 1, aes128-cts-hmac-sha1-96
Key: vno 1, des3-cbc-sha1
Key: vno 1, arcfour-hmac
MKey: vno 1
Attributes:
Policy: [none]
```

In hbase shell:

```
hbase> grant 'renewal1', 'CRXWA'
```

Be sure to add `$HADOOP_CONF_DIR` and `$HBASE_CONF_DIR` to application's classpath.
