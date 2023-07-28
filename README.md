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
	implementation 'com.github.Daimhim:imc-core:1.0.0'
}
```
