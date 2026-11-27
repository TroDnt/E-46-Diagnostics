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
 private lateinit var readings: TextView
 private lateinit var faults: TextView
 @Volatile private var live = false
 @Volatile private var ready = false
 private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
 private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,100,32,100); setBackgroundColor(0xff101820.toInt()) }
  val scroll = ScrollView(this); scroll.addView(layout); setContentView(scroll)
  layout.addView(TextView(this).apply { text = "E46 DIAGNOSTICS 0.2\n320i • M54B22 • 2001"; textSize = 24f })
  status = TextView(this).apply { text = "Не подключено" }; layout.addView(status)
  fun button(name: String, action: () -> Unit) { layout.addView(Button(this).apply { text = name; isAllCaps=false; setTextColor(0xffffffff.toInt()); backgroundTintList=android.content.res.ColorStateList.valueOf(0xff24578a.toInt()); setOnClickListener { action() } }) }
  button("Подключить ELM327") { choose() }
  button("Проверить адаптер и двигатель") { task { ready=false; for(c in listOf("ATZ","ATE0","ATL0","ATS1","ATH0","ATI","ATSP3","0100","ATDP","ATDPN","ATRV")) { val response=cmd(c); if(c=="0100") ready=Regex("41\\s*00").containsMatchIn(response.uppercase()) }; runOnUiThread { status.text=if(ready) "Двигатель на связи • ISO 9141-2" else "Нет ответа двигателя" } } }
  readings=TextView(this).apply { text="ДАННЫЕ ДВИГАТЕЛЯ\nОжидание подключения"; textSize=19f; setPadding(16,24,16,24); setBackgroundColor(0xff203040.toInt()) }; layout.addView(readings)
  button("Обновить показания") { task { requireReady(); sample() } }
  button("Запустить / остановить онлайн-данные") { if(live){live=false}else if(ready && !busy.get()){live=true; task { try { while(live && ready){sample(); Thread.sleep(800)} } finally {live=false} }} }
  faults=TextView(this).apply { text="ОШИБКИ ДВИГАТЕЛЯ\nЕщё не прочитаны"; textSize=17f; setPadding(16,24,16,24); setBackgroundColor(0xff203040.toInt()) }; layout.addView(faults)
  button("Прочитать ошибки двигателя") { task { requireReady(); decodeErrors(cmd("03")) } }
  button("Сброс ошибок двигателя") { AlertDialog.Builder(this).setTitle("Сброс OBD-II").setMessage("Только на стоянке, зажигание включено, двигатель заглушен. Удаляются ошибки, стоп-кадры и готовность экологических тестов. Причина неисправности не устраняется.").setNegativeButton("Отмена",null).setPositiveButton("Подтверждаю"){_,_-> task { requireReady(); val rpm=pid("0C"); check(rpm!=null && rpm.size>=2 && rpm[0]==0 && rpm[1]==0){"Заглушите двигатель; обороты должны быть 0"}; val response=cmd("04"); check(Regex("(?m)^\\s*44(?:\\s|$)").containsMatchIn(response)){"Сброс не подтверждён"}; decodeErrors(cmd("03")) } }.show() }
  button("Проверка возможностей K-Line") { task { requireReady(); for(c in listOf("ATDP","ATDPN","ATKW","ATIGN","AT@1","ATH1","0100","ATH0")) cmd(c) } }
  button("Отключиться") { live=false; ready=false; try { socket?.close() } catch (_: Exception) {}; socket=null; status.text="Не подключено" }
  button("Поделиться журналом") { val i=Intent(Intent.ACTION_SEND).apply { type="text/plain"; putExtra(Intent.EXTRA_TEXT,journal.toString()) }; startActivity(Intent.createChooser(i,"Журнал диагностики")) }
  button("Сохранить журнал") { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="text/plain"; putExtra(Intent.EXTRA_TITLE,"e46-log.txt") }, 22) }
  log=TextView(this).apply { textSize=13f; setTextIsSelectable(true) }; layout.addView(log)
 }
 private fun append(s:String) { runOnUiThread { journal.append(s).append('\n'); log.text=journal.toString() } }
 private fun task(action:()->Unit) { if(!busy.compareAndSet(false,true)){ Toast.makeText(this,"Остановите онлайн-данные или дождитесь завершения",Toast.LENGTH_SHORT).show(); return }; worker.execute { try { action() } catch(e:Exception) { append("Ошибка: "+e.message) } finally { busy.set(false) } } }
 private fun choose() {
  if(busy.get()) return
  ready=false; live=false
  if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){ requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),11); return }
  try { val adapter=getSystemService(BluetoothManager::class.java).adapter; if(adapter==null || !adapter.isEnabled){ append("Включите Bluetooth"); return }; val devices=adapter.bondedDevices.toList(); if(devices.isEmpty()){ append("Сначала сопрягите ELM327 в настройках Bluetooth"); return }; AlertDialog.Builder(this).setTitle("Выберите адаптер").setItems(devices.map { (it.name ?: "Bluetooth")+" ("+it.address+")" }.toTypedArray()){_,n -> task { socket?.close(); val s=devices[n].createRfcommSocketToServiceRecord(uuid); socket=s; try { s.connect() } catch(e:Exception){s.close(); socket=null; throw e}; runOnUiThread { status.text="Bluetooth подключён" }; append("Подключено. Выполните проверку адаптера.") } }.show() } catch(e:Exception){append("Ошибка Bluetooth: "+e.message)}
 }
 private fun cmd(c:String):String { val s=socket ?: error("Сначала подключите адаптер"); append("> "+c); s.outputStream.write((c+"\r").toByteArray(Charsets.US_ASCII)); s.outputStream.flush(); val input=s.inputStream; val result=StringBuilder(); val deadline=SystemClock.elapsedRealtime()+30000; while(SystemClock.elapsedRealtime()<deadline){ if(input.available()>0){ val b=input.read(); if(b<0) error("Соединение закрыто"); if(b==62){ val r=result.toString().trim(); append(r); return r }; result.append(b.toChar()) } else Thread.sleep(20) }; s.close(); socket=null; ready=false; live=false; runOnUiThread { status.text="Соединение прервано" }; error("Тайм-аут: "+c+"; "+result) }
 private fun requireReady(){check(ready){"Сначала выполните проверку двигателя"}}
 private fun pid(p:String):List<Int>? { val r=cmd("01"+p); val m=Regex("41\\s*"+p+"\\s*([0-9A-F]{2})(?:\\s*([0-9A-F]{2}))?").find(r.uppercase()) ?: return null; return m.groupValues.drop(1).mapNotNull{it.toIntOrNull(16)} }
 private fun sample(){ val out=mutableListOf<String>(); for((p,label) in listOf("0C" to "Обороты","05" to "ОЖ","0D" to "Скорость","06" to "STFT банк 1","07" to "LTFT банк 1","08" to "STFT банк 2","09" to "LTFT банк 2","0F" to "Воздух","10" to "ДМРВ","11" to "Дроссель")) { if(!ready) break; val v=pid(p); val a=v?.getOrNull(0); val b=v?.getOrNull(1); val result=if(a==null) "—" else when(p){"0C"->if(b!=null) ((a*256+b)/4.0).toString()+" об/мин" else "—";"05","0F"->(a-40).toString()+" °C";"0D"->a.toString()+" км/ч";"10"->if(b!=null) String.format(java.util.Locale.US,"%.2f г/с",(a*256+b)/100.0) else "—";"11"->String.format(java.util.Locale.US,"%.1f %%",a*100.0/255);else->String.format(java.util.Locale.US,"%+.1f %%",(a-128)*100.0/128)}; out.add(label+": "+result) }; if(ready) out.add("Напряжение: "+cmd("ATRV")); runOnUiThread { readings.text=out.joinToString("\n") } }
 private fun decodeErrors(r:String){ val codes=linkedSetOf<String>(); var valid=false; for(line in r.uppercase().lines()){ val clean=line.trim().replace(" ",""); if(!clean.startsWith("43") || !clean.matches(Regex("[0-9A-F]+")) || clean.length%2!=0) continue; valid=true; val bytes=clean.drop(2).chunked(2).mapNotNull{it.toIntOrNull(16)}; for(i in 0 until bytes.size-1 step 2){val a=bytes[i]; val b=bytes[i+1]; if(a==0 && b==0) continue; codes.add("PCBU"[a shr 6].toString()+((a shr 4) and 3).toString()+(a and 15).toString(16).uppercase()+b.toString(16).padStart(2,'0').uppercase()) } }; val dict=mapOf("P0300" to "Множественные пропуски зажигания","P1347" to "Пропуски цилиндра 3 с отключением топлива (BMW)","P1343" to "Пропуски цилиндра 1 с отключением топлива (BMW)","P0171" to "Бедная смесь, банк 1","P0174" to "Бедная смесь, банк 2","P0160" to "Нет активности датчика кислорода, банк 2 датчик 2","P0161" to "Цепь нагревателя, банк 2 датчик 2"); val result=if(!valid) "Нет подтверждённого ответа ECU" else if(codes.isEmpty()) "Сохранённых кодов OBD-II нет" else codes.joinToString("\n\n"){it+" — "+(dict[it] ?: "Расшифровка пока отсутствует")}; append(result); runOnUiThread{faults.text=result} }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data); if(requestCode==22 && resultCode==RESULT_OK){try{data?.data?.let{contentResolver.openOutputStream(it)?.use{out->out.write(journal.toString().toByteArray())}}}catch(e:Exception){append("Ошибка сохранения: "+e.message)}}}
 override fun onDestroy(){live=false; ready=false; try{socket?.close()}catch(_:Exception){}; worker.shutdownNow(); super.onDestroy()}
}