package org.tron.core.services.jsonrpc;

import com.google.common.base.Throwables;
import com.google.common.primitives.Longs;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.util.encoders.Hex;
import org.tron.api.GrpcAPI.AssetIssueList;
import org.tron.common.crypto.Hash;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.ByteUtil;
import org.tron.common.utils.DecodeUtil;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.Wallet;
import org.tron.core.exception.JsonRpcInvalidParamsException;
import org.tron.protos.Protocol.Block;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.TransactionInfo;
import org.tron.protos.contract.AccountContract.AccountCreateContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract.FrozenSupply;
import org.tron.protos.contract.AssetIssueContractOuterClass.ParticipateAssetIssueContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.TransferAssetContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.UnfreezeAssetContract;
import org.tron.protos.contract.BalanceContract.FreezeBalanceContract;
import org.tron.protos.contract.BalanceContract.TransferContract;
import org.tron.protos.contract.BalanceContract.UnfreezeBalanceContract;
import org.tron.protos.contract.ExchangeContract.ExchangeInjectContract;
import org.tron.protos.contract.ExchangeContract.ExchangeTransactionContract;
import org.tron.protos.contract.ExchangeContract.ExchangeWithdrawContract;
import org.tron.protos.contract.ShieldContract.ShieldedTransferContract;
import org.tron.protos.contract.SmartContractOuterClass.ClearABIContract;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.UpdateEnergyLimitContract;
import org.tron.protos.contract.SmartContractOuterClass.UpdateSettingContract;
import org.tron.protos.contract.VoteAssetContractOuterClass.VoteAssetContract;
import org.tron.protos.contract.WitnessContract.VoteWitnessContract;
import org.tron.protos.contract.WitnessContract.VoteWitnessContract.Vote;

@Slf4j(topic = "API")
public class JsonRpcApiUtil {
  public static byte[] convertToTronAddress(byte[] address) {
    byte[] newAddress = new byte[21];
    byte[] temp = new byte[] {Wallet.getAddressPreFixByte()};
    System.arraycopy(temp, 0, newAddress, 0, temp.length);

    if (address.length <= 20) {
      int start = 20 - address.length;
      System.arraycopy(address, 0, newAddress, temp.length + start, address.length);
    } else {
      int start = address.length - 20;
      System.arraycopy(address, start, newAddress, temp.length, 20);
    }
    return newAddress;
  }

  public static String encode58Check(byte[] input) {
    if (input == null || input.length == 0) {
      return "";
    }
    return StringUtil.encode58Check(input);
  }

  public static String getMethodSign(String method) {
    byte[] selector = new byte[4];
    System.arraycopy(Hash.sha3(method.getBytes()), 0, selector, 0, 4);
    return Hex.toHexString(selector);
  }

  public static TriggerSmartContract triggerCallContract(byte[] address, byte[] contractAddress,
      long callValue, byte[] data, long tokenValue, String tokenId) {
    TriggerSmartContract.Builder builder = TriggerSmartContract.newBuilder();

    builder.setOwnerAddress(ByteString.copyFrom(address));
    builder.setContractAddress(ByteString.copyFrom(contractAddress));
    builder.setData(ByteString.copyFrom(data));
    builder.setCallValue(callValue);

    if (StringUtils.isNotEmpty(tokenId)) {
      builder.setCallTokenValue(tokenValue);
      builder.setTokenId(Long.parseLong(tokenId));
    }

    return builder.build();
  }

  public static String getBlockID(Block block) {
    long blockNum = block.getBlockHeader().getRawData().getNumber();
    byte[] blockHash = Sha256Hash.of(true, block.getBlockHeader().getRawData().toByteArray())
        .getByteString().toByteArray();
    byte[] numBytes = Longs.toByteArray(blockNum);
    byte[] hash = new byte[blockHash.length];
    System.arraycopy(numBytes, 0, hash, 0, 8);
    System.arraycopy(blockHash, 8, hash, 8, blockHash.length - 8);
    return "0x" + ByteArray.toHexString(hash);
  }

  public static byte[] getToAddress(Transaction transaction) {
    List<ByteString> toAddressList = getTo(transaction);
    if (!toAddressList.isEmpty()) {
      return toAddressList.get(0).toByteArray();
    } else {
      return null;
    }
  }

  public static List<ByteString> getTo(Transaction transaction) {
    Transaction.Contract contract = transaction.getRawData().getContract(0);
    List<ByteString> list = new ArrayList<>();
    try {
      Any contractParameter = contract.getParameter();
      switch (contract.getType()) {
        case AccountCreateContract:
          list.add(contractParameter.unpack(AccountCreateContract.class).getAccountAddress());
          break;
        case TransferContract:
          list.add(contractParameter.unpack(TransferContract.class).getToAddress());
          break;
        case TransferAssetContract:
          list.add(contractParameter.unpack(TransferAssetContract.class).getToAddress());
          break;
        case VoteAssetContract:
          list.addAll(contractParameter.unpack(VoteAssetContract.class).getVoteAddressList());
          break;
        case VoteWitnessContract:
          for (Vote vote : contractParameter.unpack(VoteWitnessContract.class).getVotesList()) {
            list.add(vote.getVoteAddress());
          }
          break;
        case ParticipateAssetIssueContract:
          list.add(contractParameter.unpack(ParticipateAssetIssueContract.class).getToAddress());
          break;
        case FreezeBalanceContract:
          ByteString receiverAddress = contractParameter.unpack(FreezeBalanceContract.class)
              .getReceiverAddress();
          if (receiverAddress != null && !receiverAddress.isEmpty()) {
            list.add(receiverAddress);
          }
          break;
        case UnfreezeBalanceContract:
          receiverAddress = contractParameter.unpack(UnfreezeBalanceContract.class)
              .getReceiverAddress();
          if (receiverAddress != null && !receiverAddress.isEmpty()) {
            list.add(receiverAddress);
          }
          break;
        case TriggerSmartContract:
          list.add(contractParameter.unpack(TriggerSmartContract.class).getContractAddress());
          break;
        case UpdateSettingContract:
          list.add(contractParameter.unpack(UpdateSettingContract.class).getContractAddress());
          break;
        case UpdateEnergyLimitContract:
          list.add(contractParameter.unpack(UpdateEnergyLimitContract.class).getContractAddress());
          break;
        case ClearABIContract:
          list.add(contractParameter.unpack(ClearABIContract.class).getContractAddress());
          break;
        case ShieldedTransferContract:
          ShieldedTransferContract shieldedTransferContract = contract.getParameter()
              .unpack(ShieldedTransferContract.class);
          if (!shieldedTransferContract.getTransparentToAddress().isEmpty()) {
            list.add(shieldedTransferContract.getTransparentToAddress());
          }
          break;
        default:
          break;
      }
      return list;
    } catch (Exception ex) {
      ex.printStackTrace();
    } catch (Throwable t) {
      t.printStackTrace();
    }
    return list;
  }

  public static String getTxID(Transaction transaction) {
    return ByteArray.toHexString(Sha256Hash.hash(true, transaction.getRawData().toByteArray()));
  }

  public static long getTransactionAmount(Transaction.Contract contract, String hash,
      Wallet wallet) {
    long amount = 0;
    try {
      switch (contract.getType()) {
        case UnfreezeBalanceContract:
        case WithdrawBalanceContract:
          TransactionInfo transactionInfo = wallet
              .getTransactionInfoById(ByteString.copyFrom(ByteArray.fromHexString(hash)));
          amount = getAmountFromTransactionInfo(hash, contract.getType(), transactionInfo);
          break;
        default:
          amount = getTransactionAmount(contract, hash, 0, null, wallet);
          break;
      }
    } catch (Exception e) {
      logger.error("Exception happens when get amount. Exception = [{}]",
          Throwables.getStackTraceAsString(e));
    } catch (Throwable t) {
      t.printStackTrace();
    }

    return amount;
  }

  public static long getTransactionAmount(Transaction.Contract contract, String hash,
      long blockNum, TransactionInfo transactionInfo, Wallet wallet) {
    long amount = 0;
    try {
      Any contractParameter = contract.getParameter();
      switch (contract.getType()) {
        case TransferContract:
          amount = contractParameter.unpack(TransferContract.class).getAmount();
          break;
        case TransferAssetContract:
          amount = contractParameter.unpack(TransferAssetContract.class).getAmount();
          break;
        case VoteWitnessContract:
          List<Vote> votesList = contractParameter.unpack(VoteWitnessContract.class).getVotesList();
          long voteNumber = 0L;
          for (Vote vote : votesList) {
            voteNumber += vote.getVoteCount();
          }
          amount = voteNumber;
          break;
        case WitnessCreateContract:
          amount = 9999_000_000L;
          break;
        case AssetIssueContract:
        case ExchangeCreateContract:
          amount = 1024_000_000L;
          break;
        case ParticipateAssetIssueContract:
          // long token = DataImporter.getTokenID(blockNum,
          //     contractParameter.unpack(ParticipateAssetIssueContract.class).getAssetName());
          // long trxNum = contractParameter.unpack(ParticipateAssetIssueContract.class)
          // .getAmount();
          // Token10Entity entity = DataImporter.getTokenEntity(token);
          // long exchangeAmount = Math.multiplyExact(trxNum, entity.getNum());
          // exchangeAmount = Math.floorDiv(exchangeAmount, entity.getTrxNum());
          // amount = exchangeAmount;
          break;
        case FreezeBalanceContract:
          amount = contractParameter.unpack(FreezeBalanceContract.class).getFrozenBalance();
          break;
        case TriggerSmartContract:
          amount = contractParameter.unpack(TriggerSmartContract.class).getCallValue();
          break;
        case ExchangeInjectContract:
          amount = contractParameter.unpack(ExchangeInjectContract.class).getQuant();
          break;
        case ExchangeWithdrawContract:
          amount = contractParameter.unpack(ExchangeWithdrawContract.class).getQuant();
          break;
        case ExchangeTransactionContract:
          amount = contractParameter.unpack(ExchangeTransactionContract.class).getQuant();
          break;
        case AccountPermissionUpdateContract:
          amount = 100_000_000L;
          break;
        case ShieldedTransferContract:
          ShieldedTransferContract shieldedTransferContract = contract.getParameter()
              .unpack(ShieldedTransferContract.class);
          if (shieldedTransferContract.getFromAmount() > 0L) {
            amount = shieldedTransferContract.getFromAmount();
          } else if (shieldedTransferContract.getToAmount() > 0L) {
            amount = shieldedTransferContract.getToAmount();
          }
          break;
        case UnfreezeBalanceContract:
        case WithdrawBalanceContract:
          amount = getAmountFromTransactionInfo(hash, contract.getType(), transactionInfo);
          break;
        case UnfreezeAssetContract:
          amount = getUnfreezeAssetAmount(contractParameter.unpack(UnfreezeAssetContract.class)
              .getOwnerAddress().toByteArray(), wallet);
          break;
        default:
      }
    } catch (Exception e) {
      logger.error("Exception happens when get amount. Exception = [{}]",
          Throwables.getStackTraceAsString(e));
    } catch (Throwable t) {
      t.printStackTrace();
    }
    return amount;
  }

  public static long getAmountFromTransactionInfo(String hash, ContractType contractType,
      TransactionInfo transactionInfo) {
    long amount = 0L;
    try {

      if (transactionInfo != null) {

        switch (contractType) {
          case UnfreezeBalanceContract:
            amount = transactionInfo.getUnfreezeAmount();
            break;
          case WithdrawBalanceContract:
            amount = transactionInfo.getWithdrawAmount();
            break;
          case ExchangeInjectContract:
            amount = transactionInfo.getExchangeInjectAnotherAmount();
            break;
          case ExchangeWithdrawContract:
            amount = transactionInfo.getExchangeWithdrawAnotherAmount();
            break;
          case ExchangeTransactionContract:
            amount = transactionInfo.getExchangeReceivedAmount();
            break;
          default:
            break;
        }
      } else {
        logger.error("Can't find transaction {} ", hash);
      }
    } catch (Exception e) {
      logger.warn("Exception happens when get amount from transactionInfo. Exception = [{}]",
          Throwables.getStackTraceAsString(e));
    } catch (Throwable t) {
      t.printStackTrace();
    }
    return amount;
  }

  public static long getUnfreezeAssetAmount(byte[] addressBytes, Wallet wallet) {
    long amount = 0L;
    try {
      if (addressBytes == null) {
        return amount;
      }

      AssetIssueList assetIssueList = wallet
          .getAssetIssueByAccount(ByteString.copyFrom(addressBytes));
      if (assetIssueList != null) {
        if (assetIssueList.getAssetIssueCount() != 1) {
          return amount;
        } else {
          AssetIssueContract assetIssue = assetIssueList.getAssetIssue(0);
          Iterator<FrozenSupply> iterator = assetIssue.getFrozenSupplyList().iterator();
          while (iterator.hasNext()) {
            FrozenSupply frozenSupply = iterator.next();
            amount += frozenSupply.getFrozenAmount();
          }
        }
      }
    } catch (Exception e) {
      logger.warn("Exception happens when get token10 frozenAmount. Exception = [{}]",
          Throwables.getStackTraceAsString(e));
    }
    return amount;
  }

  public static byte[] addressHashToByteArray(String hash) throws JsonRpcInvalidParamsException {
    byte[] bHash;
    try {
      bHash = ByteArray.fromHexString(hash);
      if (bHash.length != DecodeUtil.ADDRESS_SIZE / 2
          && bHash.length != DecodeUtil.ADDRESS_SIZE / 2 - 1) {
        throw new JsonRpcInvalidParamsException("invalid address hash value");
      }

      if (bHash.length == DecodeUtil.ADDRESS_SIZE / 2 - 1) {
        bHash = ByteUtil.merge(new byte[] {DecodeUtil.addressPreFixByte}, bHash);
      }
    } catch (Exception e) {
      throw new JsonRpcInvalidParamsException(e.getMessage());
    }
    return bHash;
  }

  public static boolean paramStringIsNull(String string) {
    return StringUtils.isEmpty(string) || string.equals("0x");
  }

  public static boolean paramQuantityIsNull(String quantity) {
    return StringUtils.isEmpty(quantity) || quantity.equals("0x0");
  }

  public static long parseQuantityValue(String value) throws JsonRpcInvalidParamsException {
    long callValue = 0L;

    if (StringUtils.isNotEmpty(value)) {
      try {
        callValue = ByteArray.jsonHexToLong(value);
      } catch (Exception e) {
        throw new JsonRpcInvalidParamsException("invalid param value: invalid hex number");
      }
    }

    return callValue;
  }

  public static long getEnergyUsageTotal(Transaction transaction, Wallet wallet) {
    long energyUsageTotal = 0;

    byte[] txHash = Sha256Hash
        .hash(CommonParameter.getInstance().isECKeyCryptoEngine(),
            transaction.getRawData().toByteArray());
    TransactionInfo transactionInfo = wallet
        .getTransactionInfoById(ByteString.copyFrom(txHash));
    if (transactionInfo != null) {
      energyUsageTotal = transactionInfo.getReceipt().getEnergyUsageTotal();
    }

    return energyUsageTotal;
  }

  public static long getEnergyUsageTotal(List<TransactionInfo> transactionInfoList, int i,
      long blockNum) {
    long energyUsageTotal = 0;

    if (!transactionInfoList.isEmpty()) {
      try {
        energyUsageTotal = transactionInfoList.get(i).getReceipt().getEnergyUsageTotal();
      } catch (Exception e) {
        logger.warn(
            "getBlockResult cannot get energy from transactionInfo, block.num={}, error is {}",
            blockNum, e.getMessage());
      }
    }

    return energyUsageTotal;
  }

  public static int getTransactionIndex(String txId, List<Transaction> txList) {
    int transactionIndex = -1;
    Transaction transaction;

    for (int index = 0; index < txList.size(); index++) {
      transaction = txList.get(index);
      if (getTxID(transaction).equals(txId)) {
        transactionIndex = index;
        break;
      }
    }

    return transactionIndex;
  }

  public static long parseEnergyFee(long timestamp, String energyPriceHistory) {
    String[] priceList = energyPriceHistory.split(",");

    for (int i = priceList.length - 1; i >= 0; i--) {
      String[] priceArray = priceList[i].split(":");
      long time = Long.parseLong(priceArray[0]);
      long price = Long.parseLong(priceArray[1]);
      if (timestamp > time) {
        return price;
      }
    }

    return -1;
  }
}
