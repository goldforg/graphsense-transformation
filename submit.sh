#!/bin/bash

# default values
MEMORY="4g"
SPARK_MASTER="local[*]"
CASSANDRA_HOST="localhost"

CURRENCY="BTC"
RAW_KEYSPACE="btc_raw"
TAG_KEYSPACE="tagpacks"
TGT_KEYSPACE="btc_transformed"
BUCKET_SIZE=10000
MODE="full"


if [ -z "$SPARK_HOME" ] ; then
    echo "Cannot find Apache Spark. Set the SPARK_HOME environment variable." > /dev/stderr
    exit 1;
fi

EXEC=$(basename "$0")
USAGE="Usage: $EXEC [-h] [-m MEMORY_GB] [-c CASSANDRA_HOST] [-s SPARK_MASTER] [--currency CURRENCY] [--src_keyspace RAW_KEYSPACE] [--tag_keyspace TAG_KEYSPACE] [--tgt_keyspace TGT_KEYSPACE] [--bucket_size BUCKET_SIZE] [--mode full|append] [--append-block-count APPEND_BLOCK_COUNT]"

# parse command line options
args=$(getopt -o hc:m:s: --long raw_keyspace:,tag_keyspace:,tgt_keyspace:,bucket_size:,currency: -- "$@")
eval set -- "$args"

while true; do
    case "$1" in
        -h)
            echo "$USAGE"
            exit 0
        ;;
        -c)
            CASSANDRA_HOST="$2"
            shift 2
        ;;
        -m)
            MEMORY=$(printf "%dg" "$2")
            shift 2
        ;;
        -s)
            SPARK_MASTER="$2"
            shift 2
        ;;
        --currency)
            CURRENCY="$2"
            shift 2
        ;;
        --raw_keyspace)
            RAW_KEYSPACE="$2"
            shift 2
        ;;
        --tag_keyspace)
            TAG_KEYSPACE="$2"
            shift 2
        ;;
        --tgt_keyspace)
            TGT_KEYSPACE="$2"
            shift 2
        ;;
        --mode)
            MODE="$2"
            shift 2
        ;;
        --append_block_count)
            APPEND_BLOCK_COUNT="$2"
            shift 2
        ;;
        --bucket_size)
            BUCKET_SIZE="$2"
            shift 2
        ;;
        --) # end of all options
            shift
            if [ "x$*" != "x" ] ; then
                echo "$EXEC: Error - unknown argument \"$*\"" >&2
                exit 1
            fi
            break
        ;;
        -*)
            echo "$EXEC: Unrecognized option \"$1\". Use -h flag for help." >&2
            exit 1
        ;;
        *) # no more options
             break
        ;;
    esac
done


echo -en "Starting on $CASSANDRA_HOST with master $SPARK_MASTER" \
         "and $MEMORY memory ...\n" \
         "- currency:        $CURRENCY\n" \
         "- raw keyspace:    $RAW_KEYSPACE\n" \
         "- tag keyspace:    $TAG_KEYSPACE\n" \
         "- target keyspace: $TARGET_KEYSPACE\n" \
         "- bucket size:     $BUCKET_SIZE\n" \
         "- mode: :          $MODE\n"

if [ $MODE == "append" ]; then
  CLASSNAME="at.ac.ait.AppendJob"
fi
if [ $MODE == "full" ]; then
  CLASSNAME="at.ac.ait.TransformationJob"
fi

"$SPARK_HOME"/bin/spark-submit \
  --class $CLASSNAME \
  --master "$SPARK_MASTER" \
  --conf spark.executor.memory="$MEMORY" \
  --conf spark.cassandra.connection.host="$CASSANDRA_HOST" \
  --conf spark.sql.session.timeZone=UTC \
  --packages com.datastax.spark:spark-cassandra-connector_2.12:2.4.2,org.rogach:scallop_2.12:3.4.0 \
  target/scala-2.12/graphsense-transformation_2.12-0.4.5-SNAPSHOT.jar \
  --currency "$CURRENCY" \
  --raw_keyspace "$RAW_KEYSPACE" \
  --tag_keyspace "$TAG_KEYSPACE" \
  --target_keyspace "$TARGET_KEYSPACE" \
  --bucket_size "$BUCKET_SIZE" \
  --append_block_count "$APPEND_BLOCK_COUNT"

exit $?
