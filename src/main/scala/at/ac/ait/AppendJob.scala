package at.ac.ait

import at.ac.ait.clustering.MultipleInputClustering
import at.ac.ait.storage.CassandraStorage
import com.datastax.spark.connector.rdd.ValidRDDType
import com.datastax.spark.connector.rdd.reader.RowReaderFactory
import com.datastax.spark.connector.writer.RowWriterFactory
import com.datastax.spark.connector.{SomeColumns, toRDDFunctions}
import org.apache.spark.sql.functions.{col, collect_set, count, countDistinct, floor, lit, lower, size, struct}
import org.apache.spark.sql.{Dataset, Encoder, SparkSession}
import org.rogach.scallop.ScallopConf

import scala.collection.mutable
import scala.reflect.ClassTag

object AppendJob {

  class Conf(arguments: Seq[String]) extends ScallopConf(arguments) {
    val currency = opt[String](
      required = true,
      descr = "Cryptocurrency (e.g. BTC, BCH, LTC, ZEC)"
    )
    val rawKeyspace =
      opt[String]("raw_keyspace", required = true, descr = "Raw keyspace")
    val tagKeyspace =
      opt[String]("tag_keyspace", required = true, descr = "Tag keyspace")
    val targetKeyspace = opt[String](
      "target_keyspace",
      required = true,
      descr = "Transformed keyspace"
    )
    val bucketSize = opt[Int](
      "bucket_size",
      required = false,
      default = Some(25000),
      descr = "Bucket size for Cassandra partitions"
    )
    verify()
  }


  def compute(args: Array[String]): Unit = {
    val (cassandra, conf, spark) = setupCassandra(args)
    import spark.implicits._

    val disableAddressTransactions = true
    val disableAddressIds = true
    val disableRelations = true

    val exchangeRatesRaw =
      cassandra.load[ExchangeRatesRaw](conf.rawKeyspace(), "exchange_rates")
    val blocks =
      cassandra.load[Block](conf.rawKeyspace(), "block")
    val transactions =
      cassandra.load[Transaction](conf.rawKeyspace(), "transaction")
    val tagsRaw = cassandra
      .load[TagRaw](conf.tagKeyspace(), "tag_by_address")

    val lastProcessedBlock =
      cassandra.load[SummaryStatistics](conf.targetKeyspace(), "summary_statistics")
        .first().noBlocks - 1

    val unprocessedBlocks = blocks.filter((b) => {
      b.height > lastProcessedBlock
    })
    val transactionsDiff = transactions.filter((tx) => {
      tx.height > lastProcessedBlock
    })
    println()
    println("Computing unprocessed diff")
    println(s"New blocks:          ${unprocessedBlocks.count()}")
    println(s"New transactions:    ${transactionsDiff.count()}")
    println()

    val transformation = new Transformation(spark, conf.bucketSize())

    println("Computing exchange rates")
    val exchangeRates =
      transformation
        .computeExchangeRates(blocks, exchangeRatesRaw)
        .persist()

    cassandra.store(conf.targetKeyspace(), "exchange_rates", exchangeRates.filter(r => r.height > lastProcessedBlock))

    println("Extracting transaction inputs")
    val regInputs = transformation.computeRegularInputs(transactions).persist()
    val regInputsDiff = transformation.computeRegularInputs(transactionsDiff).persist()
    println("Extracting transaction outputs")
    val regOutputs = transformation.computeRegularOutputs(transactions).persist()

    println("Computing address IDs")
    val addressIds = transformation.computeAddressIds(regOutputs)

    val addressByIdGroup = transformation.computeAddressByIdGroups(addressIds)
    if (!disableAddressIds) {
      cassandra.store(
        conf.targetKeyspace(),
        "address_by_id_group",
        addressByIdGroup
      )
    }

    val addressTransactionsDiff =
      transformation
        .computeAddressTransactions(
          transactionsDiff,
          regInputs,
          regOutputs,
          addressIds
        )
        .persist()

    if (!disableAddressTransactions) {
      cassandra.store(
        conf.targetKeyspace(),
        "address_transactions",
        addressTransactionsDiff,
        ifNotExists = true
      )
    }

    val (inputsDiff, outputsDiff) =
      transformation.splitTransactions(addressTransactionsDiff)
    inputsDiff.persist()
    outputsDiff.persist()

    println("Computing address statistics")

    /**
     * All addresses affected by the newly added transactions.
     */
    val basicAddressesDiff: Dataset[BasicAddress] =
      transformation
        .computeBasicAddresses(
          transactionsDiff,
          addressTransactionsDiff,
          inputsDiff,
          outputsDiff,
          exchangeRates
        )
        .persist()

    val addressTagsDiff: Dataset[AddressTags] =
      if (tagsRaw.count() > 0) {
        println("Computing address tags")
        val addressTagsDiff =
          transformation
            .computeAddressTags(
              tagsRaw,
              basicAddressesDiff,
              addressIds,
              conf.currency()
            )
            .persist()
        cassandra.store(conf.targetKeyspace(), "address_tags", addressTagsDiff)
        val noAddressTagsDiff = addressTagsDiff
          .select(col("label"))
          .withColumn("label", lower(col("label")))
          .distinct()
          .count()
        addressTagsDiff
      }
      else {
        println("No tags available, not computing address tags!")
        spark.emptyDataset
      }

    val bucketSize = conf.bucketSize.getOrElse(0)

    if (!disableRelations) {

      println("Computing plain address relations")
      /**
       * Plain address relations derived only from unprocessed transactions (transactionsDiff)
       */
      val plainAddressRelationsDiff =
        transformation
          .computePlainAddressRelations(
            inputsDiff,
            outputsDiff,
            regInputs,
            transactionsDiff
          )

      println("Computing address relations")
      val addressRelationsDiff: Dataset[AddressRelations] =
        transformation
          .computeAddressRelations(
            plainAddressRelationsDiff,
            basicAddressesDiff,
            exchangeRates,
            addressTagsDiff
          )
          .persist()

      def sumCurrency: ((Currency, Currency) => Currency) = {
        (c1, c2) =>
          Currency(c1.value + c2.value, c1.eur + c2.eur, c1.usd + c2.usd)
      }

      def sumProperties: ((AddressSummary, AddressSummary) => AddressSummary) = {
        (a1, a2) =>
          AddressSummary(
            sumCurrency.apply(a1.totalReceived, a2.totalReceived),
            sumCurrency.apply(a1.totalSpent, a2.totalSpent)
          )
      }

      val newAndUpdatedInRelations = addressRelationsDiff.as[AddressIncomingRelations]
        .rdd.leftJoinWithCassandraTable[AddressIncomingRelations](conf.targetKeyspace(), "address_incoming_relations")
        .on(SomeColumns("src_address_id", "dst_address_id", "dst_address_id_group"))
        .map(r => {
          val existingOpt = r._2
          val diff = r._1
          if (existingOpt.isDefined) {
            val existing = existingOpt.get
            val noTransactions = existing.noTransactions + diff.noTransactions
            val estimatedValue = sumCurrency.apply(existing.estimatedValue, diff.estimatedValue)
            val srcLabels = (Option(existing.srcLabels).getOrElse(Seq()) ++ Option(diff.srcLabels).getOrElse(Seq())).distinct
            existing.copy(noTransactions = noTransactions, estimatedValue = estimatedValue, srcLabels = srcLabels)
          } else {
            diff
          }
        })

      val newAndUpdatedOutRelations = addressRelationsDiff.as[AddressOutgoingRelations]
        .rdd.leftJoinWithCassandraTable[AddressOutgoingRelations](conf.targetKeyspace(), "address_outgoing_relations")
        .on(SomeColumns("src_address_id", "dst_address_id", "src_address_id_group"))
        .map(r => {
          val existingOpt = r._2
          val diff = r._1
          if (existingOpt.isDefined) {
            val existing = existingOpt.get
            val noTransactions = existing.noTransactions + diff.noTransactions
            val estimatedValue = sumCurrency.apply(existing.estimatedValue, diff.estimatedValue)
            val dstLabels = (Option(existing.dstLabels).getOrElse(Seq()) ++ Option(diff.dstLabels).getOrElse(Seq())).distinct
            val txList = ((Option(existing.txList).getOrElse(Seq())) ++ Option(diff.txList).getOrElse(Seq())).distinct
            existing.copy(noTransactions = noTransactions, estimatedValue = estimatedValue, dstLabels = dstLabels, txList = txList)
          } else {
            diff
          }
        })

      println("Updating address relations")
      cassandra.store[AddressIncomingRelations](conf.targetKeyspace(), "address_incoming_relations", newAndUpdatedInRelations.toDS())
      cassandra.store[AddressOutgoingRelations](conf.targetKeyspace(), "address_outgoing_relations", newAndUpdatedOutRelations.toDS())


      val props: ((Currency, Currency) => AddressSummary) = { case(totalReceived, totalSpent) => AddressSummary(totalReceived, totalSpent) }

      println("Computing addresses")
      val addressesDiff =
        transformation.computeAddresses(
          basicAddressesDiff,
          addressRelationsDiff,
          addressIds
        )


      val noChangedAddresses = addressesDiff.count()

      /**
       * Information about all affected addresses, merged with their previous state in cassandra.
       */
      val mergedAddresses = addressesDiff
        .rdd
        .leftJoinWithCassandraTable[Address](conf.targetKeyspace(), "address")
        .on(SomeColumns("address_prefix", "address"))
        .map({
          case (diff, existingOpt) =>
            if (existingOpt.isEmpty) {
              diff
            } else {
              val existing = existingOpt.get
              existing.copy(
                noIncomingTxs = existing.noIncomingTxs + diff.noIncomingTxs,
                noOutgoingTxs = existing.noOutgoingTxs + diff.noOutgoingTxs,
                firstTx = if (diff.firstTx.height < existing.firstTx.height) diff.firstTx else existing.firstTx,
                lastTx = if (diff.lastTx.height > existing.lastTx.height) diff.lastTx else existing.lastTx,
                totalReceived = sumCurrency.apply(existing.totalReceived, diff.totalReceived),
                totalSpent = sumCurrency.apply(existing.totalSpent, diff.totalSpent),
                inDegree = existing.inDegree + diff.inDegree,
                outDegree = existing.outDegree + diff.outDegree
              )
            }
        })
        .toDS()

      val addressRelationPairs = addressesDiff
        .withColumnRenamed(Fields.addressId, Fields.dstAddressId)
        .withColumn(Fields.dstAddressIdGroup, floor(col(Fields.dstAddressId).divide(lit(bucketSize))))
        .select(Fields.dstAddressIdGroup, Fields.dstAddressId)
        .rdd
        .joinWithCassandraTable[AddressIncomingRelations](conf.targetKeyspace(), "address_incoming_relations")
        .on(SomeColumns("dst_address_id_group", "dst_address_id"))
        .map({
          case (row, relations) =>
            val dstAddressIdGroup = row.getLong(0)
            val dstAddressId = row.getInt(1)
            AddressPair(relations.srcAddressId, -1, dstAddressId, dstAddressIdGroup.intValue())
        })
        .toDS()
        .withColumn(Fields.srcAddressIdGroup, floor(col(Fields.srcAddressId) / lit(bucketSize)).cast("int"))
        .as[AddressPair]

      println("Updating srcProperties field of address_incoming_relations...")
      val inRelationsUpdated = mergedAddresses
        .join(addressRelationPairs, joinExprs = col(Fields.addressId) equalTo col(Fields.srcAddressId))
        .as[AddressPairWithProperties]
        .rdd
        .joinWithCassandraTable[AddressIncomingRelations](conf.targetKeyspace(), "address_incoming_relations")
        .on(SomeColumns("dst_address_id_group", "dst_address_id", "src_address_id"))
        .map({
          case (addressPair, relations) =>
            relations.copy(
              srcProperties = AddressSummary(
                totalReceived = addressPair.totalReceived,
                totalSpent = addressPair.totalSpent
              )
            )
        }).toDS()
      cassandra.store[AddressIncomingRelations](conf.targetKeyspace(), "address_incoming_relations", inRelationsUpdated)

      println("Updating dstProperties field of address_outgoing_relations...")
      val outRelationsUpdated = mergedAddresses
        .join(addressRelationPairs, joinExprs = col(Fields.addressId) equalTo col(Fields.dstAddressId))
        .as[AddressPairWithProperties]
        .rdd
        .joinWithCassandraTable[AddressOutgoingRelations](conf.targetKeyspace(), "address_outgoing_relations")
        .on(SomeColumns("src_address_id_group", "src_address_id", "dst_address_id"))
        .map({
          case (addressPair, relations) =>
            relations.copy(
              dstProperties = AddressSummary(
                totalReceived = addressPair.totalReceived,
                totalSpent = addressPair.totalSpent
              )
            )
        }).toDS()
      cassandra.store[AddressOutgoingRelations](conf.targetKeyspace(), "address_outgoing_relations", outRelationsUpdated)

      cassandra.store(conf.targetKeyspace(), "address", mergedAddresses)
    }


    spark.sparkContext.setJobDescription("Perform clustering")

    println("Computing address clusters")
    val addressClusterDiff = transformation
      .computeAddressCluster(regInputsDiff, addressIds, true)
      .persist()
    //cassandra.store(conf.targetKeyspace(), "address_cluster", addressCluster)
    val addressClusterExtDiff = transformation.extendBasicClusterAddresses(addressClusterDiff)

    // computeBasicClusterAddresses will perform a join operation between basicAddressesDiff and addressClusterDiff.
    // Since basicAddressesDiff will not contain addresses that were not in any transaction since `lastProcessedBlock`,
    // that operation will not return any rows for these addresses.
    println("Computing basic cluster addresses")
    val basicClusterAddressesDiff =
      transformation
        .computeBasicClusterAddressesExt(basicAddressesDiff, addressClusterExtDiff)
        .persist()

    println("Basic cluster addresses diff for cluster 9166: ")
    basicClusterAddressesDiff.filter(r => r.cluster == 9166).show(100, false)


    val cassandraClusters = basicClusterAddressesDiff
      .rdd
      .leftJoinWithCassandraTable[AddressCluster](conf.targetKeyspace(), "address_cluster")
      .on(SomeColumns("address_id_group", "address_id"))
      .groupBy(r => r._1.cluster) // Group by cluster id that we have just clustered
      .map({
        case (cluster, tuples) =>
          val addressList = tuples
            .flatMap({
              case (localCluster, cassandraCluster) =>
                // these two clusters (local, i.e. in spark memory and cassandra, i.e. stored in cassandra)
                // definitely have a shared address since we just joined on address id in `joinWithCassandraTable`!
                val addresses = mutable.MutableList(localCluster.addressId)
                if (cassandraCluster.isDefined)
                  addresses += cassandraCluster.get.addressId
                addresses
            })

          val clusterGroup = Math.floorDiv(cluster, bucketSize)

          ClusterPart(
            clusterGroup = clusterGroup,
            cluster = cluster,
            addressList = addressList.map(addrId => {
              CassandraAddressCluster(
                addressIdGroup = Math.floorDiv(addrId, bucketSize),
                addressId = addrId,
                clusterGroup = clusterGroup,
                cluster = cluster
              )
            }).toSet
          )
      }).toDS()

    val withCassandraAddresses = cassandraClusters
      .rdd
      .joinWithCassandraTable[ClusterAddresses](conf.targetKeyspace(), "cluster_addresses")
      .on(SomeColumns("cluster_group", "cluster"))
      .map({
        case (part, otherAddressInCassandraCluster) =>
          val cassandraAddressCluster = CassandraAddressCluster(
            addressIdGroup = Math.floorDiv(otherAddressInCassandraCluster.addressId, bucketSize),
            addressId = otherAddressInCassandraCluster.addressId,
            clusterGroup = otherAddressInCassandraCluster.clusterGroup,
            cluster = otherAddressInCassandraCluster.cluster
          )

          part.copy(addressList = part.addressList + cassandraAddressCluster)
      })

    val newAddressGroups = withCassandraAddresses
      .toDS()
      .select(col(Fields.cluster), size(col("addressList")), col("addressList"))


    println("Reassing addresses: ")
    newAddressGroups.show(100, false)
  }

  def verify(args: Array[String]): Unit = {
    val (cassandra, conf, spark) = setupCassandra(args)
    import spark.implicits._

    val verifyTables = List(
//      "address_by_id_group",
//      "address_transactions",
      "address_incoming_relations",
      "address_outgoing_relations",
      "address"
    )


    def verifyTable[T <: Product: ClassTag: RowReaderFactory: ValidRDDType: Encoder](table: String, sortBy: String) {
      println(s"Calculating difference: $table")
      val byAppendJob = cassandra.load[T](conf.targetKeyspace(), table)
        .sort(sortBy)

      val byTransformJob = cassandra.load[T]("btc_transformed", table)
        .sort(sortBy)

      byAppendJob.union(byTransformJob).except(byAppendJob.intersect(byTransformJob)).as[T].show()
    }

    if (verifyTables contains "address_transactions") {
      verifyTable[AddressTransactions]("address_transactions", Fields.addressId)
    }

    if (verifyTables contains "address_by_id_group") {
      verifyTable[AddressByIdGroup]("address_by_id_group", Fields.addressId)
    }

    if (verifyTables contains "address_incoming_relations") {
      verifyTable[AddressIncomingRelations]("address_incoming_relations", Fields.dstAddressId)
    }

    if (verifyTables contains "address_outgoing_relations") {
      verifyTable[AddressOutgoingRelations]("address_outgoing_relations", Fields.srcAddressId)
    }

    if (verifyTables contains "address") {
      verifyTable[Address]("address", Fields.addressId)
    }
  }

  def setupCassandra(args: Array[String]): (CassandraStorage, Conf, SparkSession) = {
    val conf = new Conf(args)

    val spark = SparkSession.builder
      .appName("GraphSense Iterative Transformation [%s]".format(conf.targetKeyspace()))
      .getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    println("Currency:        " + conf.currency())
    println("Raw keyspace:    " + conf.rawKeyspace())
    println("Tag keyspace:    " + conf.tagKeyspace())
    println("Target keyspace: " + conf.targetKeyspace())
    println("Bucket size:     " + conf.bucketSize())

    val cassandra = new CassandraStorage(spark)
    (cassandra, conf, spark)
  }

  /**
   * Restores some of the tables of btc_transformed_append to the state it was in btc_tranaformed10k
   * NOTE: Make sure you truncate the tables before running, this method does not delete rows!
   * @param args
   * @param fromKeyspace
   * @param toKeyspace
   */
  def restore(args: Array[String], fromKeyspace: String = "btc_transformed10k", toKeyspace: String = "btc_transformed_append"): Unit = {
    val (cassandra, conf, spark) = setupCassandra(args)
    import spark.implicits._

    val tablesToRestore = List(
      "address_incoming_relations",
      "address_outgoing_relations",
      "address"
    )


    def restoreTable[T <: Product: ClassTag: RowReaderFactory: RowWriterFactory: ValidRDDType: Encoder](table: String): Unit = {
      println(s"Restore: Writing table ${table} from keyspace ${fromKeyspace} to ${toKeyspace}")
      val addrInRelations = cassandra.load[T](fromKeyspace, table)
      cassandra.store[T](toKeyspace, table, addrInRelations.as[T])
    }


    if (tablesToRestore contains "address_incoming_relations")
      restoreTable[AddressIncomingRelations]("address_incoming_relations")

    if (tablesToRestore contains "address_outgoing_relations")
      restoreTable[AddressOutgoingRelations]("address_outgoing_relations")

    if (tablesToRestore contains "address")
      restoreTable[Address]("address")


  }

  def main(args: Array[String]) {
//    verify(args)
//    restore(args)
    compute(args)
  }
}
