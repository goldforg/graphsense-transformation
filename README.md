[![Build Status](https://travis-ci.org/graphsense/graphsense-transformation.svg?branch=develop)](https://travis-ci.org/graphsense/graphsense-transformation)

# GraphSense Transformation Pipeline

The GraphSense Transformation Pipeline reads raw block data, which is
ingested into [Apache Cassandra][apache-cassandra]
by the [graphsense-blocksci][graphsense-blocksci] component, and
attribution tags provided by [graphsense-tagpacks][graphsense-tagpacks].
The transformation pipeline computes de-normalized views using
[Apache Spark][apache-spark], which are again stored in Cassandra.

Access to computed de-normalized views is subsequently provided by the
[GraphSense REST][graphsense-rest] interface, which is used by the
[graphsense-dashboard][graphsense-dashboard] component.

This component is implemented in Scala using [Apache Spark][apache-spark].

## Quick docker setup

### Prerequisites
Make sure the latest versions of Docker and docker-compose are installed. https://docs.docker.com/compose/install/

This service assumes that:
 - There is a cassandra instance running;
 - Both parser and exporter from `graphsense-blocksci` have completed fetching data into that cassandra instance.
 
**It is possible to set up all required services using a single docker-compose evironment. For that, check out the `graphsense-setup` project.** Alternatively, you can set up each required service manually, in which case, keep on reading.

### Configure
Create a new configuration by copying the `env.example` file to `.env`.
Modify the configuration match your environment, or keep everything intact.
 - `CASSANDRA_HOST` must point to an existing cassandra instance.

Apply the configuation by adding this line to `docker-compose.yml`:
```yaml
services:
    transform:
        ...
        env_file: .env
        ...
```

### Build 
`docker-compose build`

### Run
`docker-compose up -d`

Use `docker-compose logs -f --tail=100` to find out about the progress.


## Local Development Environment Setup

### Prerequisites

Make sure [Java 8][java] and [sbt >= 1.0][scala-sbt] is installed:

    java -version
    sbt about

Download, install, and run [Apache Spark][apache-spark] (version 2.4.7)
in `$SPARK_HOME`:

    $SPARK_HOME/sbin/start-master.sh

Download, install, and run [Apache Cassandra][apache-cassandra]
(version >= 3.11) in `$CASSANDRA_HOME`

    $CASSANDRA_HOME/bin/cassandra -f

### Ingest Raw Block Data

Run the following script for ingesting raw block test data

    ./scripts/ingest_test_data.sh

This should create a keyspace `btc_raw` (tables `exchange_rates`,
`transaction`, `block`, `block_transactions`) and `tagpacks`
(table `tag_by_address`). Check as follows

    cqlsh localhost
    cqlsh> USE btc_raw;
    cqlsh:btc_raw> DESCRIBE tables;
    cqlsh:btc_raw> USE tagpacks;
    cqlsh:tagpacks> DESCRIBE tables;

## Execute Transformation Locally

macOS only: make sure `gnu-getopt` is installed

    brew install gnu-getopt

Create the target keyspace for transformed data

    ./scripts/create_target_schema.sh

Compile and test the implementation

    sbt test

Package the transformation pipeline

    sbt package

Run the transformation pipeline on localhost

    ./submit.sh

Check the running job using the local Spark UI at http://localhost:4040/jobs

# Submit on a standalone Spark Cluster

Use the `submit.sh` script and specify the Spark master node
(e.g., `-s spark://SPARK_MASTER_IP:7077`) and other options:

```
./submit.sh -h
Usage: submit.sh [-h] [-m MEMORY_GB] [-c CASSANDRA_HOST] [-s SPARK_MASTER]
                 [--currency CURRENCY] [--src_keyspace RAW_KEYSPACE]
                 [--tag_keyspace TAG_KEYSPACE] [--tgt_keyspace TGT_KEYSPACE]
                 [--bucket_size BUCKET_SIZE]
```

# Submit to an external standalone Spark Cluster using Docker

See the [GraphSense Setup][graphsense-setup] component, i.e., the README
file and the `transformation` subdirectory.


[graphsense-blocksci]: https://github.com/graphsense/graphsense-blocksci
[graphsense-tagpacks]: https://github.com/graphsense/graphsense-tagpacks
[graphsense-dashboard]: https://github.com/graphsense/graphsense-dashboard
[graphsense-rest]: https://github.com/graphsense/graphsense-rest
[graphsense-setup]: https://github.com/graphsense/graphsense-setup
[java]: https://java.com
[scala-ide]: http://scala-ide.org/
[scala-lang]: https://www.scala-lang.org/
[scala-sbt]: http://www.scala-sbt.org
[sbteclipse]: https://github.com/typesafehub/sbteclipse
[apache-spark]: https://spark.apache.org/downloads.html
[apache-cassandra]: http://cassandra.apache.org/
