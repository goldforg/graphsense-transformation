package info.graphsense

import java.sql.Date

case class RegularInput(
    address: String,
    txId: Long,
    value: Long,
    blockId: Int,
    timestamp: Int,
    coinJoin: Boolean
)

case class RegularOutput(
    address: String,
    txId: Long,
    value: Long,
    blockId: Int,
    timestamp: Int,
    coinJoin: Boolean,
    n: Int
)

case class AddressId(
    address: String,
    addressId: Int
)

case class AddressByAddressPrefix(
    addressPrefix: String,
    address: String,
    addressId: Int
)

case class InputIdSet(inputs: Seq[Int]) extends Iterable[Int] {
  override def iterator = inputs.iterator
}

// raw schema data types

case class TxInputOutput(address: Seq[String], value: Long, addressType: Byte)

// transformed schema data types

case class TxSummary(
    txHash: Array[Byte],
    noInputs: Int,
    noOutputs: Int,
    totalInput: Long,
    totalOutput: Long
)

case class Currency(value: Long, fiatValues: Seq[Float])

// raw schema tables

case class Block(
    blockIdGroup: Int,
    blockId: Int,
    blockHash: Array[Byte],
    timestamp: Int,
    noTransactions: Int
)

case class Transaction(
    txIdGroup: Long,
    txId: Long,
    txHash: Array[Byte],
    blockId: Int,
    timestamp: Int,
    coinbase: Boolean,
    coinjoin: Boolean,
    totalInput: Long,
    totalOutput: Long,
    inputs: Seq[TxInputOutput],
    outputs: Seq[TxInputOutput]
)

case class BlockTransactions(blockId: Int, txs: Seq[TxSummary])

case class ExchangeRatesRaw(
    date: String,
    fiatValues: Option[Map[String, Float]]
  )

case class AddressTagRaw(
    address: String,
    currency: String,
    label: String,
    source: String,
    tagpackUri: String,
    lastmod: Date,
    category: Option[String],
    abuse: Option[String]
)

case class ClusterTagRaw(
    entity: Int,
    currency: String,
    label: String,
    source: String,
    tagpackUri: String,
    lastmod: Date,
    category: Option[String],
    abuse: Option[String]
)

case class SummaryStatisticsRaw(
    id: String,
    timestamp: Int,
    noBlocks: Int,
    noTxs: Long
)

// transformed schema tables

case class ExchangeRates(blockId: Int, fiatValues: Seq[Float])

case class AddressTransaction(
    addressIdGroup: Int,
    addressId: Int,
    txId: Long,
    value: Long,
    blockId: Int,
    isOutgoing: Boolean
)

case class BasicAddress(
    addressId: Int,
    noIncomingTxs: Int,
    noOutgoingTxs: Int,
    firstTxId: Long,
    lastTxId: Long,
    totalReceived: Currency,
    totalSpent: Currency
)

case class Address(
    addressIdGroup: Int,
    addressId: Int,
    address: String,
    clusterId: Int,
    noIncomingTxs: Int,
    noOutgoingTxs: Int,
    firstTxId: Long,
    lastTxId: Long,
    totalReceived: Currency,
    totalSpent: Currency,
    inDegree: Int,
    outDegree: Int
)

case class AddressTag(
    addressIdGroup: Int,
    addressId: Int,
    address: String,
    label: String,
    source: String,
    tagpackUri: String,
    lastmod: Int,
    category: String,
    abuse: String
)

case class AddressTagByLabel(
    labelNormPrefix: String,
    labelNorm: String,
    label: String,
    address: String,
    source: String,
    tagpackUri: String,
    currency: String,
    lastmod: Int,
    category: Option[String],
    abuse: Option[String],
    active: Boolean
)

case class AddressCluster(addressId: Int, clusterId: Int)

case class ClusterAddress(
    clusterIdGroup: Int,
    clusterId: Int,
    addressId: Int,
)

case class ClusterTransaction(
    clusterIdGroup: Int,
    clusterId: Int,
    txId: Long,
    value: Long,
    blockId: Int,
    isOutgoing: Boolean
)

case class BasicCluster(
    clusterId: Int,
    noAddresses: Int,
    noIncomingTxs: Int,
    noOutgoingTxs: Int,
    firstTxId: Long,
    lastTxId: Long,
    totalReceived: Currency,
    totalSpent: Currency
)

case class Cluster(
    clusterIdGroup: Int,
    clusterId: Int,
    noAddresses: Int,
    noIncomingTxs: Int,
    noOutgoingTxs: Int,
    firstTxId: Long,
    lastTxId: Long,
    totalReceived: Currency,
    totalSpent: Currency,
    inDegree: Int,
    outDegree: Int
)

case class ClusterTag(
    clusterIdGroup: Int,
    clusterId: Int,
    label: String,
    source: String,
    tagpackUri: String,
    lastmod: Int,
    category: String,
    abuse: String
)

case class ClusterAddressTag(
    clusterIdGroup: Int,
    clusterId: Int,
    addressId: Int,
    label: String,
    source: String,
    tagpackUri: String,
    lastmod: Int,
    category: String,
    abuse: String
)

case class ClusterTagByLabel(
    labelNormPrefix: String,
    labelNorm: String,
    label: String,
    clusterId: Int,
    source: String,
    tagpackUri: String,
    currency: String,
    lastmod: Int,
    category: Option[String],
    abuse: Option[String],
    active: Boolean
)

case class PlainAddressRelation(
    txId: Long,
    srcAddressId: Int,
    dstAddressId: Int,
    blockId: Int,
    estimatedValue: Long
)

case class AddressRelation(
    srcAddressIdGroup: Int,
    srcAddressId: Int,
    dstAddressIdGroup: Int,
    dstAddressId: Int,
    hasSrcLabels: Boolean,
    hasDstLabels: Boolean,
    noTransactions: Int,
    estimatedValue: Currency
)

case class PlainClusterRelation(
    txId: Long,
    srcClusterId: Int,
    dstClusterId: Int,
    estimatedValue: Long,
    blockId: Int
)

case class ClusterRelation(
    srcClusterIdGroup: Int,
    srcClusterId: Int,
    dstClusterIdGroup: Int,
    dstClusterId: Int,
    hasSrcLabels: Boolean,
    hasDstLabels: Boolean,
    noTransactions: Int,
    estimatedValue: Currency
)

case class SummaryStatistics(
    timestamp: Int,
    noBlocks: Int,
    noTransactions: Long,
    noAddresses: Long,
    noAddressRelations: Long,
    noClusters: Long,
    noTags: Long
)

case class Configuration(
    keyspaceName: String,
    bucketSize: Int,
    addressPrefixLength: Int,
    bech32Prefix: String,
    labelPrefixLength: Int,
    coinjoinFiltering: Boolean,
    fiatCurrencies: Seq[String]
)
