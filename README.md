# imc-core

在okhttp的基础上增加了心跳、自动连接等动作

### Step 1. Add the JitPack repository to your build file
Add it in your root build.gradle at the end of repositories:
```
	allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}
```
### Step 2. Add the dependency
```
dependencies {
	implementation 'com.github.Daimhim:imc-core:1.0.6'
}
```
### 常用API
+ engineOn(key:String) 同步启动 会等到服务彻底打开
+ engineOff() 关闭
+ engineState():Int 主动获取服务器状态
+ send(byteArray: ByteArray):Boolean  发送bytearray消息
+ send(text: String):Boolean 发送文本消息
+ addIMCListener(imcListener: V2IMCListener) // 添加新消息监听
+ removeIMCListener(imcListener: V2IMCListener) 移除新消息监听
+ addIMCSocketListener(level:Int, imcSocketListener: V2IMCSocketListener    ) 添加新消息拦截器，level代表优先级。默认15
+ removeIMCSocketListener(imcSocketListener: V2IMCSocketListener) 移除新消息拦截器
+ setIMCStatusListener(listener: IMCStatusListener?)  监听socket连接状态
+ onChangeMode(mode:Int) 切换心跳频率 0=5秒  1=45秒
+ onNetworkChange(networkState:Int) 网络切换时调用，用于重置抢救机制
+ makeConnection() 主动尝试重新连接
### 重点核心RapidResponseForce
RapidResponseForce
#### 功能：
1. 可以批量分组的形式，同时对多个目标进行超时限定
#### 优点：
1. 核心使用了一个线程进行超时检测
2. 在无任务超过限定时间后，会自动进入休眠
#### 实现：
1. 通过队列实现任务的插入、移除
2. 在PowerTrainRunnable中优先执行，任务的增删改操作
3. 全局共享一个waitingReaction对象来存储任务

