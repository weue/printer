package com.mo.erosplugingprinter;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.weex.plugin.annotation.WeexModule;
import com.taobao.weex.annotation.JSMethod;
import com.taobao.weex.bridge.JSCallback;
import com.taobao.weex.common.WXModule;
import com.tools.command.EscCommand;
import com.tools.command.LabelCommand;
import com.tools.io.BluetoothPort;


import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by MA on 17/10/18.
 */
@WeexModule(name = "BluetoothModule", lazyLoad = true)
public class BluetoothModule extends WXModule {

  private final static String BLUETOOTH_MODULE = "BluetoothModule";

  private BluetoothUtil mBluetoothUtil = null;

  private ArrayList<BluetoothDevice> mBondDevices = null;

  private ArrayList<BluetoothDevice> mUnbondDevices = null;

  private BluetoothPort mPort = null;


  private Vector<Byte> datas = null;

  private JSCallback onDiscovery;
  private JSCallback onDiscoveryOver;
  private JSCallback tscCallback;



  public BluetoothModule() {
    mBluetoothUtil = new BluetoothUtil(BluetoothAdapter.getDefaultAdapter());
    mBondDevices = new ArrayList<>();
    mUnbondDevices = new ArrayList<>();
  }

  //    @Override
//    public String getName() {
//        return BLUETOOTH_MODULE;
//    }
  private  final String TAG = BluetoothModule.this.getClass().getName();

  /**
   * 是否支持蓝牙
   */
  @JSMethod(uiThread = false)
  public void isSupport(JSCallback callback) {
    boolean support = mBluetoothUtil.isSupportBluetooth();
    Log.i(TAG, "isSupport: "+support);
    callback.invoke(support);
  }

  /**
   * 蓝牙是否启用
   *
   * @param callback
   */
  @JSMethod
  public void isEnabled(JSCallback callback) {
    boolean open = mBluetoothUtil.isEnabled();
    Log.i(TAG, "isEnabled: "+open);
    callback.invoke(open);
  }

  /**
   * 启用蓝牙
   */
  @JSMethod
  public void enableBluetooth() {
    Log.i(TAG, "enableBluetooth: ");
    mBluetoothUtil.enableBluetooth(this.mWXSDKInstance.getContext());
  }

  /**
   * 获取已配对设备
   */
  @JSMethod
  public void getBondedDevices(JSCallback callback) {
    ArrayList<BluetoothDevice> btDevices = mBluetoothUtil.getBondedDevices();
    List devices = new ArrayList();
    for (int i = 0; i < btDevices.size(); i++) {
      HashMap bondedDevices = new HashMap();
      bondedDevices.put("deviceName", btDevices.get(i).getName());
      bondedDevices.put("deviceAddress", btDevices.get(i).getAddress());
      bondedDevices.put("type", btDevices.get(i).getBluetoothClass().getMajorDeviceClass());
      mBondDevices.add(btDevices.get(i));
      devices.add(bondedDevices);
    }
    callback.invoke(devices);
  }

  /**
   * 开始搜索设备
   *
   * @param callback 发现新设备的回调
   * @param callback2 搜索结束的回调
   */
  @JSMethod
  public void searchDevices(JSCallback callback,JSCallback callback2) {
    this.onDiscovery = callback;
    this.onDiscoveryOver = callback2;
    mBluetoothUtil.registerBluetoothReceiver(this.mWXSDKInstance.getContext(), searchDevicesReceiver);
    mBluetoothUtil.startDiscoveryDevices();
  }

  /**
   * 停止搜索设备
   *
   * @param
   */
  @JSMethod (uiThread = false)
  public void stopSearchDevices(){
    mBluetoothUtil.cancelDisoveryDevices();
  }

  /**
   * 搜索设备的广播
   */
  public BroadcastReceiver searchDevicesReceiver = new BroadcastReceiver() {
    String unbondAddress = "";

    @Override
    public void onReceive(Context context, Intent intent) {
      String action = intent.getAction();

      if (BluetoothDevice.ACTION_FOUND.equals(action)) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        // 如果已经配对则存在列表里，直接跳过
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
          // 是否已经搜索过，避免重复
          if (!unbondAddress.contains(device.getAddress())) {
            mUnbondDevices.add(device);
            HashMap unBondDevices = new HashMap();
            unBondDevices.put("deviceName", device.getName());
            unBondDevices.put("deviceAddress", device.getAddress());


            unbondAddress += device.getAddress()+"|";
            onDiscovery.invokeAndKeepAlive(unBondDevices);
            Log.i(TAG, "onReceive: "+device.getName()+":"+device.getAddress());
          }
        }
      } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
        //搜索结束解除注册
        BluetoothModule.this.mWXSDKInstance.getContext().unregisterReceiver(this);
        mBluetoothUtil.cancelDisoveryDevices();
        //运行js中的onDiscoveryOver回调函数
        onDiscoveryOver.invoke("搜索结束");
      }
    }
  };

  /**
   * 配对设备
   *
   * @param deviceAddress 根据设备地址进行设备的配对
   * @param callback      返回配对成功信息
   */
  @JSMethod
  public void bondDevice(String deviceAddress, JSCallback callback) {
    boolean isBonded = false;

    mPort = new BluetoothPort(deviceAddress);
    isBonded = mPort.openPort();
    callback.invoke(isBonded);
  }


  /**
   * 断开打印机的连接
   */
  @JSMethod
  public void disconnectPrinter() {
    if (mPort != null) {
      mPort.closePort();
      mPort = null;
//      mCommand = null;
    }
  }

  private LabelCommand.BARCODETYPE findBarcodeType(String type) {
    LabelCommand.BARCODETYPE barcodeType = LabelCommand.BARCODETYPE.CODE128;
    for (LabelCommand.BARCODETYPE t : LabelCommand.BARCODETYPE.values()) {
      if (type.equals(t.getValue())) {
        barcodeType = t;
        break;
      }
    }
    return barcodeType;
  }

  private LabelCommand.EEC findEEC(String level) {
    LabelCommand.EEC eec = LabelCommand.EEC.LEVEL_L;
    for (LabelCommand.EEC e : LabelCommand.EEC.values()) {
      if (level.equals(e.getValue())) {
        eec = e;
        break;
      }
    }
    return eec;
  }

  private LabelCommand.READABEL findReadabel(int readabel) {
    LabelCommand.READABEL ea = LabelCommand.READABEL.EANBEL;
    if (LabelCommand.READABEL.DISABLE.getValue() == readabel) {
      ea = LabelCommand.READABEL.DISABLE;
    }
    return ea;
  }

  private LabelCommand.FONTMUL findFontMul(int scan) {
    LabelCommand.FONTMUL mul = LabelCommand.FONTMUL.MUL_1;
    for (LabelCommand.FONTMUL m : LabelCommand.FONTMUL.values()) {
      if (m.getValue() == scan) {
        mul = m;
        break;
      }
    }
    return mul;
  }

  private LabelCommand.ROTATION findRotation(int rotation) {
    LabelCommand.ROTATION rt = LabelCommand.ROTATION.ROTATION_0;
    for (LabelCommand.ROTATION r : LabelCommand.ROTATION.values()) {
      if (r.getValue() == rotation) {
        rt = r;
        break;
      }
    }
    return rt;
  }

  private LabelCommand.FONTTYPE findFontType(String fonttype) {
    LabelCommand.FONTTYPE ft = LabelCommand.FONTTYPE.SIMPLIFIED_CHINESE;
    for (LabelCommand.FONTTYPE f : LabelCommand.FONTTYPE.values()) {
      if (fonttype.equals(f.getValue())) {
        ft = f;
        break;
      }
    }
    return ft;
  }

  private LabelCommand.BITMAP_MODE findBitmapMode(int mode){
    LabelCommand.BITMAP_MODE bm = LabelCommand.BITMAP_MODE.OVERWRITE;
    for (LabelCommand.BITMAP_MODE m : LabelCommand.BITMAP_MODE.values()) {
      if (m.getValue() == mode) {
        bm = m;
        break;
      }
    }
    return bm;
  }

  private LabelCommand.SPEED findSpeed(int speed){
    LabelCommand.SPEED sd = null;
    switch(speed){
      case 1:
        sd = LabelCommand.SPEED.SPEED1DIV5;
        break;
      case 2:
        sd = LabelCommand.SPEED.SPEED2;
        break;
      case 3:
        sd = LabelCommand.SPEED.SPEED3;
        break;
      case 4:
        sd = LabelCommand.SPEED.SPEED4;
        break;
    }
    return sd;
  }

  private LabelCommand.DENSITY findDensity(int density){
    LabelCommand.DENSITY ds = null;
    for (LabelCommand.DENSITY d : LabelCommand.DENSITY.values()) {
      if (d.getValue() == density) {
        ds = d;
        break;
      }
    }
    return ds;
  }


  /**
   * 打印标签
   *
   * @param options json
   */
  @JSMethod
  public void printLabel(final JSONObject options) {
    int count = options.containsKey("count") ? options.getInteger("count") : 1;
    int width = options.getInteger("width");
    int height = options.getInteger("height");
    int gap = options.containsKey("gap") ? options.getInteger("gap") : 0;
    LabelCommand.SPEED speed = options.containsKey("speed")?this.findSpeed(options.getInteger("speed")):null;
    EscCommand.ENABLE tear = options.containsKey("tear") ?
            options.getInteger("tear") == EscCommand.ENABLE.ON.getValue() ? EscCommand.ENABLE.ON : EscCommand.ENABLE.OFF
            : EscCommand.ENABLE.OFF;
    JSONArray texts = options.getJSONArray("text");
    JSONArray qrCodes = options.getJSONArray("qrcode");
    JSONArray barCodes = options.getJSONArray("barcode");
    JSONArray images = options.getJSONArray("image");
    JSONArray reverses = options.getJSONArray("reverse");

    LabelCommand.DIRECTION direction = options.containsKey("direction") ?
            LabelCommand.DIRECTION.BACKWARD.getValue() == options.getInteger("direction") ? LabelCommand.DIRECTION.BACKWARD : LabelCommand.DIRECTION.FORWARD
            : LabelCommand.DIRECTION.FORWARD;
    LabelCommand.MIRROR mirror = options.containsKey("mirror") ?
            LabelCommand.MIRROR.MIRROR.getValue() == options.getInteger("mirror") ? LabelCommand.MIRROR.MIRROR : LabelCommand.MIRROR.NORMAL
            : LabelCommand.MIRROR.NORMAL;
    LabelCommand.DENSITY density = options.containsKey("density")?this.findDensity(options.getInteger("density")):null;
    JSONArray reference = options.getJSONArray("reference");

    boolean sound = false;
    if (options.containsKey("sound") && options.getInteger("sound") == 1) {
      sound = true;
    }
    LabelCommand tsc = new LabelCommand();
    if(speed != null){
      tsc.addSpeed(speed);//设置打印速度
    }
    if(density != null){
      tsc.addDensity(density);//设置打印浓度
    }
    tsc.addSize(width,height); //设置标签尺寸，按照实际尺寸设置
    tsc.addGap(gap);           //设置标签间隙，按照实际尺寸设置，如果为无间隙纸则设置为0
    tsc.addDirection(direction, mirror);//设置打印方向
    //设置原点坐标
    if (reference != null && reference.size() == 2) {
      tsc.addReference(reference.getInteger(0), reference.getInteger(1));
    } else {
      tsc.addReference(0, 0);
    }
    tsc.addTear(tear); //撕纸模式开启
    tsc.addCls();// 清除打印缓冲区
    //绘制简体中文
    for (int i = 0; i < texts.size(); i++) {
      JSONObject text = texts.getJSONObject(i);
      String t = text.getString("text");
      int x = text.getInteger("x");
      int y = text.getInteger("y");
      LabelCommand.FONTTYPE fonttype = this.findFontType(text.getString("fonttype"));
      LabelCommand.ROTATION rotation = this.findRotation(text.getInteger("rotation"));
      LabelCommand.FONTMUL xscal = this.findFontMul(text.getInteger("xscal"));
      LabelCommand.FONTMUL yscal = this.findFontMul(text.getInteger("xscal"));
      boolean bold = text.containsKey("bold") && text.getBoolean("bold");

      try {
        byte[] temp = t.getBytes("UTF-8");
        String temStr = new String(temp, "UTF-8");
        t = new String(temStr.getBytes("GB2312"), "GB2312");//打印的文字
      } catch (Exception e) {
//        promise.reject("INVALID_TEXT", e);
        return;
      }

      tsc.addText(x, y, fonttype/*字体类型*/,
              rotation/*旋转角度*/, xscal/*横向放大*/, yscal/*纵向放大*/, t);

      if(bold){
//                tsc.addText(x-1, y, fonttype,
//                        rotation, xscal, yscal, t/*这里的t可能需要替换成同等长度的空格*/);
        tsc.addText(x+1, y, fonttype,
                rotation, xscal, yscal, t/*这里的t可能需要替换成同等长度的空格*/);
//                tsc.addText(x, y-1, fonttype,
//                        rotation, xscal, yscal, t/*这里的t可能需要替换成同等长度的空格*/);
        tsc.addText(x, y+1, fonttype,
                rotation, xscal, yscal, t/*这里的t可能需要替换成同等长度的空格*/);
      }
    }

    //绘制图片
//          Bitmap b = BitmapFactory.decodeResource(getResources(),
//            				R.drawable.gprinter);
//          tsc.addBitmap(20,50, BITMAP_MODE.OVERWRITE, b.getWidth(),b);

    if(images != null){
      for (int i = 0; i < images.size(); i++) {
        JSONObject img = images.getJSONObject(i);
        int x = img.getInteger("x");
        int y = img.getInteger("y");
        int imgWidth = img.getInteger("width");
        LabelCommand.BITMAP_MODE mode = this.findBitmapMode(img.getInteger("mode"));
        String image  = img.getString("image");
        byte[] decoded = Base64.decode(image, Base64.DEFAULT);
        Bitmap b = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        tsc.addBitmap(x,y, mode, imgWidth,b);
      }
    }

    if (qrCodes != null) {
      for (int i = 0; i < qrCodes.size(); i++) {
        JSONObject qr = qrCodes.getJSONObject(i);
        int x = qr.getInteger("x");
        int y = qr.getInteger("y");
        int qrWidth = qr.getInteger("width");
        LabelCommand.EEC level = this.findEEC(qr.getString("level"));
        LabelCommand.ROTATION rotation = this.findRotation(qr.getInteger("rotation"));
        String code = qr.getString("code");
        tsc.addQRCode(x, y, level, qrWidth, rotation, code);
      }
    }
    if (barCodes != null) {
      for (int i = 0; i < barCodes.size(); i++) {
        JSONObject bar = barCodes.getJSONObject(i);
        int x = bar.getInteger("x");
        int y = bar.getInteger("y");
        int barHeight = bar.getInteger("height");
        LabelCommand.ROTATION rotation = this.findRotation(bar.getInteger("rotation"));
        String code = bar.getString("code");
        LabelCommand.BARCODETYPE type = this.findBarcodeType(bar.getString("type"));
        LabelCommand.READABEL readabel = this.findReadabel(bar.getInteger("readabel"));
        tsc.add1DBarcode(x, y, type, barHeight, readabel, rotation, code);
      }
    }

    if(reverses != null){
      for(int i=0; i < reverses.size(); i++){
        JSONObject area = reverses.getJSONObject(i);
        int ax = area.getInteger("x");
        int ay = area.getInteger("y");
        int aWidth = area.getInteger("width");
        int aHeight = area.getInteger("height");
        tsc.addReverse(ax,ay,aWidth,aHeight);
      }
    }

    /* 打印标签 count为打印的份数 */
    tsc.addPrint( count, 1 );
    /* 打印标签后 蜂鸣器响 */
    tsc.addSound( 2, 100 );
    /* 开启钱箱 */
//    tsc.addCashdrwer( LabelCommand.FOOT.F5, 255, 255 );
    Vector<Byte> datas = tsc.getCommand();
    /* 发送数据 */
    if (this.mPort == null) {
//      callback.invoke("打印机未连接");
      return;
    }
    try {
      //  Log.e(TAG, "data -> " + new String(com.gprinter.command.GpUtils.convertVectorByteTobytes(data), "gb2312"));
      this.mPort.writeDataImmediately(datas);
    } catch (Exception e) {
      e.printStackTrace();
    }

//    sendLabelCommand(tsc, address, promise);
  }

  public int readDataImmediately(byte[] buffer) throws IOException {
    return this.mPort.readData(buffer);
  }

  /**
   * 判断打印机所使用指令是否是ESC指令
   */

  public PrinterReader reader;
//  private byte[]		tscmode		= { 0x1f, 0x1b, 0x1f, (byte) 0xfc, 0x01, 0x02, 0x03, 0x33 };

  /**
   * TSC查询打印机状态指令
   */
  private byte[] tsc = {0x1b, '!', '?'};

  /**
   * TSC指令查询打印机实时状态 打印机缺纸状态
   */
  private static final int TSC_STATE_PAPER_ERR = 0x04;

  /**
   * TSC指令查询打印机实时状态 打印机正在打印
   */
  private static final int TSC_STATE_PRINTING = 0x20;

  /**
   * TSC指令查询打印机实时状态 打印机开盖状态
   */
  private static final int TSC_STATE_COVER_OPEN = 0x01;

  /**
   * TSC指令查询打印机实时状态 打印机出错状态
   */
  private static final int TSC_STATE_ERR_OCCURS = 0x80;

  private boolean isTsc = false;

  private HashMap queryResult = new HashMap();

  class PrinterReader extends Thread {
    private boolean isRun = false;

    private byte[] buffer = new byte[100];

    public PrinterReader() {
      isRun = true;
    }

    @Override
    public void run() {
      try {
        while (isRun) {
          //读取打印机返回信息
          int len = readDataImmediately(buffer);
          if (len > 0) {
                if (buffer[0] == 0) {//正常
                  queryResult.put("status",0);
                  queryResult.put("message","正常");
                }
                else if ((buffer[0] & TSC_STATE_PAPER_ERR) > 0) {//缺纸
                  queryResult.put("status",4);
                  queryResult.put("message","打印机缺纸");
                }
                else if ((buffer[0] & TSC_STATE_COVER_OPEN) > 0) {//开盖
                  queryResult.put("status",1);
                  queryResult.put("message","打印机开盖");
                }
                else if ((buffer[0] & TSC_STATE_ERR_OCCURS) > 0) {//打印机报错
                  queryResult.put("status",80);
                  queryResult.put("message","其它错误");
                }
                else if ((buffer[0] & TSC_STATE_PRINTING) > 0) {//打印机正在打印
                  queryResult.put("status",20);
                  queryResult.put("message","打印机正在打印");
                }
                else {
                  queryResult.put("status",98);
                  queryResult.put("message","打印机异常");
                }
                isTsc = true;
                isRun = false;
                tscCallback.invoke(queryResult);
          }
        }
      } catch (Exception e) {
        // 断开连接
//        if (deviceConnFactoryManagers[id] != null) {
//          closePort(id);
//        }
      }
    }

    public void cancel() {
      isRun = false;
    }
  }


  public void sendDataImmediately(final Vector<Byte> data) {
    if (this.mPort == null) {
      return;
    }
    try {
      this.mPort.writeDataImmediately(data, 0, data.size());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @JSMethod
  public void queryTsc(JSCallback callback) {
    this.tscCallback = callback;
    isTsc = false;
    //开启读取打印机返回数据线程
    reader = new PrinterReader();
    reader.start(); //读取数据线程
    // 发送TSC查询指令
    final byte[] tsc = {0x1b, '!', '?'};
    Vector<Byte> data = new Vector<>(tsc.length);
    for (int i = 0; i < tsc.length; i++) {
      data.add(tsc[i]);
    }
    sendDataImmediately(data);
    final ThreadFactoryBuilder threadFactoryBuilder = new ThreadFactoryBuilder("Timer");
    final ScheduledExecutorService scheduledExecutorService = new ScheduledThreadPoolExecutor(1, threadFactoryBuilder);
    //开启计时器，隔2000毫秒打印机没有响应者停止读取打印机数据线程并且关闭端口
    scheduledExecutorService.schedule(threadFactoryBuilder.newThread(new Runnable() {
      @Override
      public void run() {
          if (reader != null && !isTsc) {
            // 修改打印模式为tsc
//            Vector<Byte> data = new Vector<>( tscmode.length );
//            for ( int i = 0; i < tscmode.length; i++ )
//            {
//              data.add( tscmode[i] );
//            }
//            sendDataImmediately(data);
            reader.cancel();
            mPort.closePort();
            mPort=null;
            queryResult.put("status",99);
            queryResult.put("message","请切换到标签模式");
            tscCallback.invoke(queryResult);
          }
      }
    }),2000,TimeUnit.MILLISECONDS);
  }
}