package com.e46.diagnostics
import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import java.util.UUID
import java.util.concurrent.Executors
class MainActivity : Activity() {
 private val worker = Executors.newSingleThreadExecutor()
 @Volatile private var socket: BluetoothSocket? = null
 private lateinit var log: TextView
 private lateinit var status: TextView
 private val journal = StringBuilder()
 private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,100,24,100) }
  val scroll = ScrollView(this); scroll.addView(layout); setContentView(scroll)
  layout.addView(TextView(this).apply { text = "E46 DIAGNOSTICS 0.1\n320i • M54B22 • 2001"; textSize = 24f })
  status = TextView(this).apply { text = "Не подключено" }; layout.addView(status)
  fun button(name: String, action: () -> Unit) { layout.addView(Button(this).apply { text = name; setOnClickListener { action() } }) }
  button("Подключить ELM327") { choose() }
  button("Проверить адаптер и двигатель") { task { for(c in listOf("ATZ","ATE0","ATL0","ATS1","ATH0","ATI","ATSP0","0100","ATDP","ATDPN","ATRV")) cmd(c) } }
  button("Прочитать ошибки двигателя") { task { decodeErrors(cmd("03")) } }
  button("Показания двигателя") { task { for ((pid,label) in listOf("0C" to "Обороты", "05" to "Температура ОЖ", "0D" to "Скорость")) { val r=cmd("01"+pid); val m=Regex("41\\s*"+pid+"\\s*([0-9A-F]{2})(?:\\s*([0-9A-F]{2}))?").find(r.uppercase()); if(m!=null) { val a=m.groupValues[1].toInt(16); val b=m.groupValues[2].toIntOrNull(16); val value=when(pid){"0C" -> if(b!=null) ((a*256+b)/4.0).toString()+" об/мин" else "нет данных"; "05" -> (a-40).toString()+" °C"; else -> a.toString()+" км/ч"}; append(label+": "+value) } }; cmd("ATRV") } }
  button("Отключиться") { try { socket?.close() } catch (_: Exception) {}; socket=null; status.text="Не подключено" }
  button("Поделиться журналом") { val i=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,journal.toString()) }; startActivity(Intent.createChooser(i,"Журнал диагностики")) }
  button("Сохранить журнал") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="text/plain"; putExtra(Intent.EXTRA_TITLE,"e46-log.txt") }, 22) }
  log=TextView(this).apply { textSize=13f; setTextIsSelectable(true) }; layout.addView(log)
 }
 private fun append(s:String) { runOnUiThread { journal.append(s).append('\n'); log.text=journal.toString() } }
 private fun task(action:()->Unit) { worker.execute { try { action() } catch(e:Exception) { append("Ошибка: "+e.message) } } }
 private fun choose() {
  if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),11); return }
  try { val adapter=getSystemService(BluetoothManager::class.java).adapter; if(adapter==null || !adapter.isEnabled){ append("Включите Bluetooth"); return }; val devices=adapter.bondedDevices.toList(); if(devices.isEmpty()){ append("Сначала сопрягите ELM327 в настройках Bluetooth"); return }; AlertDialog.Builder(this).setTitle("Выберите адаптер").setItems(devices.map { (it.name ?: "Bluetooth")+" ("+it.address+")" }.toTypedArray()){_,n -> task { socket?.close(); val s=devices[n].createRfcommSocketToServiceRecord(uuid); socket=s; try { s.connect() } catch(e:Exception){s.close(); socket=null; throw e}; runOnUiThread { status.text="Bluetooth подключён" }; append("Подключено. Выполните проверку адаптера.") } }.show() } catch(e:Exception){append("Ошибка Bluetooth: "+e.message)}
 }
 private fun cmd(c:String):String { val s=socket ?: error("Сначала подключите адаптер"); append("> "+c); s.outputStream.write((c+"\r").toByteArray(Charsets.US_ASCII)); s.outputStream.flush(); val input=s.inputStream; val result=StringBuilder(); val deadline=SystemClock.elapsedRealtime()+30000; while(SystemClock.elapsedRealtime()<deadline){ if(input.available()>0){ val b=input.read(); if(b<0) error("Соединение закрыто"); if(b==62){ val r=result.toString().trim(); append(r); return r }; result.append(b.toChar()) } else Thread.sleep(20) }; s.close(); socket=null; runOnUiThread { status.text="Соединение прервано" }; error("Тайм-аут: "+c+"; "+result) }
 private fun decodeErrors(r:String){ for(line in r.uppercase().lines()){ val clean=line.trim().replace(" ",""); if(!clean.startsWith("43")) continue; val bytes=clean.drop(2).chunked(2).mapNotNull{it.toIntOrNull(16)}; for(i in 0 until bytes.size-1 step 2){val a=bytes[i]; val b=bytes[i+1]; if(a==0 && b==0) continue; append("Код (предварительно): "+"PCBU"[a shr 6]+((a shr 4) and 3).toString()+(a and 15).toString(16).uppercase()+b.toString(16).padStart(2,'0').uppercase()) } } }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data); if(requestCode==22 && resultCode==RESULT_OK){try{data?.data?.let{contentResolver.openOutputStream(it)?.use{out->out.write(journal.toString().toByteArray())}}}catch(e:Exception){append("Ошибка сохранения: "+e.message)}}}
 override fun onDestroy(){try{socket?.close()}catch(_:Exception){}; worker.shutdownNow(); super.onDestroy()}
}