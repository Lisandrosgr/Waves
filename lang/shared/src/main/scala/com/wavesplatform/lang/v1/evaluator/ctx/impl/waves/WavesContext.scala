package com.wavesplatform.lang.v1.evaluator.ctx.impl.waves

import com.wavesplatform.lang.v1.CTX
import com.wavesplatform.lang.v1.compiler.Types._
import com.wavesplatform.lang.v1.evaluator.FunctionIds._
import com.wavesplatform.lang.v1.evaluator.ctx._
import com.wavesplatform.lang.v1.evaluator.ctx.impl.EnvironmentFunctions
import com.wavesplatform.lang.v1.traits._
import monix.eval.Coeval
import scodec.bits.ByteVector

object WavesContext {

  import Bindings._
  import Types._

  def build(env: Environment): CTX = {
    val environmentFunctions = new EnvironmentFunctions(env)

    def getdataF(name: String, internalName: Short, dataType: DataType): BaseFunction =
      PredefFunction(name, 100, internalName, OPTION(dataType.innerType), "address" -> addressType.typeRef, "key" -> STRING) {
        case (addr: CaseObj) :: (k: String) :: Nil => environmentFunctions.getData(addr, k, dataType)
        case _                                     => ???
      }

    val getLongF: BaseFunction      = getdataF("getLong", DATA_LONG, DataType.Long)
    val getBooleanF: BaseFunction   = getdataF("getBoolean", DATA_BOOLEAN, DataType.Boolean)
    val getByteArrayF: BaseFunction = getdataF("getByteArray", DATA_BYTES, DataType.ByteArray)
    val getStringF: BaseFunction    = getdataF("getString", DATA_STRING, DataType.String)

    val addressFromPublicKeyF: BaseFunction =
      PredefFunction("addressFromPublicKey", 100, ADDRESSFROMPUBKEY, addressType.typeRef, "publicKey" -> BYTEVECTOR) {
        case (pk: ByteVector) :: Nil =>
          val r = environmentFunctions.addressFromPublicKey(pk)
          Right(CaseObj(addressType.typeRef, Map("bytes" -> r)))
        case _ => ???
      }

    val addressFromStringF: BaseFunction = PredefFunction("addressFromString", 100, ADDRESSFROMSTRING, optionAddress, "string" -> STRING) {
      case (addressString: String) :: Nil =>
        val r = environmentFunctions.addressFromString(addressString)
        r.map(_.map(x => CaseObj(addressType.typeRef, Map("bytes" -> x))))
      case _ => ???
    }

    val addressFromRecipientF: BaseFunction =
      PredefFunction("addressFromRecipient", 100, ADDRESSFROMRECIPIENT, addressType.typeRef, "AddressOrAlias" -> addressOrAliasType) {
        case (c @ CaseObj(addressType.typeRef, _)) :: Nil => Right(c)
        case c @ CaseObj(aliasType.typeRef, fields) :: Nil =>
          environmentFunctions
            .addressFromAlias(fields("alias").asInstanceOf[String])
            .map(resolved => CaseObj(addressType.typeRef, Map("bytes" -> resolved.bytes)))
        case _ => ???
      }

    val txCoeval: Coeval[Either[String, CaseObj]]  = Coeval.evalOnce(Right(transactionObject(env.transaction)))
    val heightCoeval: Coeval[Either[String, Long]] = Coeval.evalOnce(Right(env.height))

    val txByIdF: BaseFunction = {
      val returnType = OPTION(anyTransactionType)
      PredefFunction("getTransactionById", 100, GETTRANSACTIONBYID, returnType, "id" -> BYTEVECTOR) {
        case (id: ByteVector) :: Nil =>
          val maybeDomainTx = env.transactionById(id.toArray).map(transactionObject)
          Right(maybeDomainTx).map(_.asInstanceOf[returnType.Underlying])
        case _ => ???
      }
    }

    val accountBalanceF: BaseFunction = PredefFunction("accountBalance", 100, ACCOUNTBALANCE, LONG, "addressOrAlias" -> addressOrAliasType) {
      case CaseObj(_, fields) :: Nil =>
        val acc = fields("bytes").asInstanceOf[ByteVector].toArray
        env.accountBalanceOf(acc, None)

      case _ => ???
    }

    val accountAssetBalanceF: BaseFunction =
      PredefFunction("accountAssetBalance", 100, ACCOUNTASSETBALANCE, LONG, "addressOrAlias" -> addressOrAliasType, "assetId" -> BYTEVECTOR) {
        case CaseObj(_, fields) :: (assetId: ByteVector) :: Nil =>
          val acc = fields("bytes").asInstanceOf[ByteVector]
          env.accountBalanceOf(acc.toArray, Some(assetId.toArray))

        case _ => ???
      }

    val txHeightByIdF: BaseFunction = PredefFunction("transactionHeightById", 100, TRANSACTIONHEIGHTBYID, OPTION(LONG), "id" -> BYTEVECTOR) {
      case (id: ByteVector) :: Nil => Right(env.transactionHeightById(id.toArray))
      case _                       => ???
    }

    val vars: Map[String, (TYPE, LazyVal)] = Map(
      ("height", (LONG, LazyVal.lift(heightCoeval))),
      ("tx", (outgoingTransactionType, LazyVal.lift(txCoeval)))
    )

    val functions = Seq(
      txByIdF,
      txHeightByIdF,
      getLongF,
      getBooleanF,
      getByteArrayF,
      getStringF,
      addressFromPublicKeyF,
      addressFromStringF,
      addressFromRecipientF,
      accountBalanceF,
      accountAssetBalanceF
    )

    CTX(Types.wavesTypes, vars, functions)
  }
}
