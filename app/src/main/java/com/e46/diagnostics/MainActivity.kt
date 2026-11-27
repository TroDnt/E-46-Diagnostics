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
 private val tiles=mutableMapOf<String,TextView>()
 private lateinit var count: TextView
 private val handler=Handler(Looper.getMainLooper())
 private var active=false
 @Volatile private var pollingEnabled=true
 private val poll=object:Runnable { override fun run(){ if(active && pollingEnabled && ready && !busy.get()) task { sample() }; if(active) handler.postDelayed(this,500) } }
 private lateinit var faults: TextView
 @Volatile private var live = false
 @Volatile private var ready = false
 private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
 private val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
 private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
 private fun label(t:String,size:Float=16f,color:Int=0xffe6edf5.toInt())=TextView(this).apply{text=t;textSize=size;setTextColor(color);setPadding(dp(8),dp(8),dp(8),dp(8))}
 private fun panel()=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
 private fun button(p:LinearLayout,t:String,action:()->Unit){p.addView(Button(this).apply{text=t;isAllCaps=false;setTextColor(-1);backgroundTintList=android.content.res.ColorStateList.valueOf(0xff24578a.toInt());setOnClickListener{action()}})}
 override fun onCreate(savedInstanceState:Bundle?){
 super.onCreate(savedInstanceState)
 window.statusBarColor=0xff101820.toInt();window.navigationBarColor=0xff101820.toInt()
 val root=panel().apply{setBackgroundColor(0xff101820.toInt());setPadding(dp(12),dp(12),dp(12),dp(8))}
 setContentView(root)
 root.addView(label("BMW E46 • 320i",24f))
 status=label("Не подключено",14f,0xff65d99a.toInt());root.addView(status)
 val scroll=ScrollView(this);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
 val pages=panel();scroll.addView(pages)
 val dash=panel();val errors=panel();val blocks=panel();val settings=panel()
 val screens=listOf(dash,errors,blocks,settings)
 screens.forEach{pages.addView(it);it.visibility=android.view.View.GONE};dash.visibility=android.view.View.VISIBLE
 button(dash,"Подключить ELM327"){choose()}
 val pairs=listOf("Обороты" to "ОЖ","Напряжение" to "ДМРВ","STFT банк 1" to "STFT банк 2","LTFT банк 1" to "LTFT банк 2","Скорость" to "Воздух","Дроссель" to "")
 for((left,right) in pairs){val row=LinearLayout(this);dash.addView(row);for(key in listOf(left,right)){val tile=panel().apply{setPadding(dp(8),dp(8),dp(8),dp(8));background=android.graphics.drawable.GradientDrawable().apply{setColor(0xff203040.toInt());cornerRadius=dp(14).toFloat()}};row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(dp(4),dp(4),dp(4),dp(4))});if(key.isNotEmpty()){tile.addView(label(key,13f,0xffaab9cb.toInt()));val v=label("—",25f,0xff80baff.toInt());tile.addView(v);tiles[key]=v}else tile.visibility=android.view.View.INVISIBLE}}
 count=label("Ошибки: ещё не прочитаны",18f);dash.addView(count)
 faults=label("Ещё не прочитаны",17f);errors.addView(faults)
 button(errors,"Прочитать ошибки"){pollingEnabled=false;task(true){requireReady();decodeErrors(cmd("03"))}}
 button(errors,"Сброс ошибок двигателя"){pollingEnabled=false;AlertDialog.Builder(this).setTitle("Сброс OBD-II").setMessage("Заглушите мотор, оставьте зажигание включённым. Будут удалены ошибки, стоп-кадры и готовность экологических тестов.").setNegativeButton("Отмена",null).setPositiveButton("Сбросить"){_,_->pollingEnabled=false;task(true){requireReady();val rpm=pid("0C");check(rpm!=null && rpm.size>=2 && rpm[0]==0 && rpm[1]==0){"ЭБУ показывает работающий двигатель или не отвечает. Заглушите двигатель, оставьте зажигание включённым и повторите."};append("Отправляю OBD Mode 04");val response=cmd("04");check(Regex("(?m)^\\s*44(?:\\s|$)").containsMatchIn(response)){"ЭБУ не подтвердил сброс"};runOnUiThread{Toast.makeText(this,"ЭБУ подтвердил сброс",Toast.LENGTH_LONG).show()};decodeErrors(cmd("03"))}}.show()}
 blocks.addView(label("Модули BMW E46",22f));blocks.addView(label("DME: связь подтверждена по ISO 9141-2.\nASC/ABS, SRS, IHKA, LCM и KOMBI: протокол и доступ пока не подтверждены. Обычный ELM327 может не поддерживать адресный обмен BMW.\n\nНиже только безопасная проверка возможностей адаптера. Это НЕ сканирование ошибок других блоков."));button(blocks,"Проверить интерфейс K-Line"){task{check(socket!=null){"Подключите адаптер"};val responses=mutableListOf<String>();for(c in listOf("ATI","ATDP","ATDPN","ATKW","ATIGN")){responses.add(c+": "+cmd(c).replace("\r"," ").replace("\n"," "))};append("Проверка интерфейса завершена");runOnUiThread{AlertDialog.Builder(this).setTitle("Результат проверки K-Line").setMessage(responses.joinToString("\n")+"\n\nЭто сведения об адаптере, а не подтверждение связи с ABS/SRS.").setPositiveButton("ОК",null).show()}}}
 button(settings,"Выбрать другой адаптер"){choose(false)}
 button(settings,"Отключиться"){ready=false;try{socket?.close()}catch(_:Exception){};socket=null;status.text="Не подключено";tiles.values.forEach{it.text="—"}}
 button(settings,"Поделиться журналом"){startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,journal.toString())},"Журнал диагностики"))}
 button(settings,"Сохранить журнал"){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="text/plain";putExtra(Intent.EXTRA_TITLE,"e46-log.txt")},22)}
 log=label("",12f);log.setTextIsSelectable(true);settings.addView(log)
 val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(8),0,dp(8))};root.addView(nav)
 listOf("Приборы","Ошибки","Блоки","Настройки").forEachIndexed{i,t->nav.addView(Button(this).apply{text=t;isAllCaps=false;textSize=10f;minHeight=dp(52);minimumHeight=dp(52);setPadding(dp(1),dp(4),dp(1),dp(4));setTextColor(-1);backgroundTintList=android.content.res.ColorStateList.valueOf(0xff24578a.toInt());setOnClickListener{pollingEnabled=(i==0);screens.forEachIndexed{j,v->v.visibility=if(i==j)android.view.View.VISIBLE else android.view.View.GONE};if(i==3)log.text=journal.toString();scroll.scrollTo(0,0)}},LinearLayout.LayoutParams(0,-2,1f))}
 if(getPreferences(0).contains("adapter")) choose()
 }
 override fun onStart(){super.onStart();active=true;handler.post(poll)}
 override fun onStop(){active=false;handler.removeCallbacks(poll);super.onStop()}
 private fun append(s:String) { runOnUiThread { journal.append(s).append('\n'); if(journal.length>120000) journal.delete(0,journal.length-100000); if(log.isShown) log.text=journal.toString() } }
 private fun task(priority:Boolean=false,action:()->Unit){if(!priority && !busy.compareAndSet(false,true))return;worker.execute{busy.set(true);try{action()}catch(e:Exception){append("Ошибка: "+e.message);runOnUiThread{Toast.makeText(this,e.message ?: "Ошибка связи",Toast.LENGTH_LONG).show();if(!ready)status.text="Нет связи"}}finally{busy.set(false)}}}
 private fun choose(useSaved:Boolean=true){
 if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT),11);return}
 try{val adapter=getSystemService(BluetoothManager::class.java).adapter;check(adapter!=null && adapter.isEnabled){"Включите Bluetooth"};val devices=adapter.bondedDevices.toList();check(devices.isNotEmpty()){"Сначала сопрягите ELM327 в настройках Bluetooth"}
 val saved=devices.firstOrNull{it.address==getPreferences(0).getString("adapter","")}
 if(useSaved && saved!=null){connect(saved);return}
 AlertDialog.Builder(this).setTitle("Выберите адаптер").setItems(devices.map{(it.name ?: "Bluetooth")+" ("+it.address+")"}.toTypedArray()){_,n->connect(devices[n])}.show()
 }catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}
 }
 private fun connect(device:BluetoothDevice){ready=false;status.text="Подключение…";task{ready=false;socket?.close();val s=device.createRfcommSocketToServiceRecord(uuid);socket=s;try{s.connect();for(c in listOf("ATZ","ATE0","ATL0","ATS1","ATH0","ATI","ATSP3","0100","ATDP","ATDPN")){val response=cmd(c);if(c=="0100")check(Regex("41\\s*00").containsMatchIn(response.uppercase())){"Нет ответа двигателя"}};ready=true;runOnUiThread{getPreferences(0).edit().putString("adapter",device.address).apply();status.text="● Двигатель на связи"};decodeErrors(cmd("03"))}catch(e:Exception){ready=false;s.close();socket=null;throw e}}}
 private fun cmd(c:String):String { val s=socket ?: error("Сначала подключите адаптер"); append("> "+c); s.outputStream.write((c+"\r").toByteArray(Charsets.US_ASCII)); s.outputStream.flush(); val input=s.inputStream; val result=StringBuilder(); val deadline=SystemClock.elapsedRealtime()+30000; while(SystemClock.elapsedRealtime()<deadline){ if(input.available()>0){ val b=input.read(); if(b<0) error("Соединение закрыто"); if(b==62){ val r=result.toString().trim(); append(r); return r }; result.append(b.toChar()) } else Thread.sleep(20) }; s.close(); socket=null; ready=false; live=false; runOnUiThread { status.text="Соединение прервано" }; error("Тайм-аут: "+c+"; "+result) }
 private fun requireReady(){check(ready){"Сначала выполните проверку двигателя"}}
 private fun pid(p:String):List<Int>? { val r=cmd("01"+p); val m=Regex("41\\s*"+p+"\\s*([0-9A-F]{2})(?:\\s*([0-9A-F]{2}))?").find(r.uppercase()) ?: return null; return m.groupValues.drop(1).mapNotNull{it.toIntOrNull(16)} }
 private fun sample(){ val out=mutableListOf<String>(); for((p,label) in listOf("0C" to "Обороты","05" to "ОЖ","0D" to "Скорость","06" to "STFT банк 1","07" to "LTFT банк 1","08" to "STFT банк 2","09" to "LTFT банк 2","0F" to "Воздух","10" to "ДМРВ","11" to "Дроссель")) { if(!ready) break; val v=pid(p); val a=v?.getOrNull(0); val b=v?.getOrNull(1); val result=if(a==null) "—" else when(p){"0C"->if(b!=null) ((a*256+b)/4.0).toString()+" об/мин" else "—";"05","0F"->(a-40).toString()+" °C";"0D"->a.toString()+" км/ч";"10"->if(b!=null) String.format(java.util.Locale.US,"%.2f г/с",(a*256+b)/100.0) else "—";"11"->String.format(java.util.Locale.US,"%.1f %%",a*100.0/255);else->String.format(java.util.Locale.US,"%+.1f %%",(a-128)*100.0/128)}; runOnUiThread { tiles[label]?.text=result } }; if(ready){val voltage=cmd("ATRV");runOnUiThread{tiles["Напряжение"]?.text=voltage}} }
 private fun decodeErrors(r:String){ val codes=linkedSetOf<String>(); var valid=false; for(line in r.uppercase().lines()){ val clean=line.trim().replace(" ",""); if(!clean.startsWith("43") || !clean.matches(Regex("[0-9A-F]+")) || clean.length%2!=0) continue; valid=true; val bytes=clean.drop(2).chunked(2).mapNotNull{it.toIntOrNull(16)}; for(i in 0 until bytes.size-1 step 2){val a=bytes[i]; val b=bytes[i+1]; if(a==0 && b==0) continue; codes.add("PCBU"[a shr 6].toString()+((a shr 4) and 3).toString()+(a and 15).toString(16).uppercase()+b.toString(16).padStart(2,'0').uppercase()) } }; val dict=mapOf("P0300" to "Множественные пропуски зажигания","P1347" to "Пропуски цилиндра 3 с отключением топлива (BMW)","P1343" to "Пропуски цилиндра 1 с отключением топлива (BMW)","P0171" to "Бедная смесь, банк 1","P0174" to "Бедная смесь, банк 2","P0160" to "Нет активности датчика кислорода, банк 2 датчик 2","P0161" to "Цепь нагревателя, банк 2 датчик 2"); val result=if(!valid) "Нет подтверждённого ответа ECU" else if(codes.isEmpty()) "Сохранённых кодов OBD-II нет" else codes.joinToString("\n\n"){it+" — "+(dict[it] ?: "Расшифровка пока отсутствует")}; append(result); runOnUiThread{faults.text=result;count.text=if(valid) "Ошибки двигателя: "+codes.size else "Ошибки: нет ответа"} }
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data); if(requestCode==22 && resultCode==RESULT_OK){try{data?.data?.let{contentResolver.openOutputStream(it)?.use{out->out.write(journal.toString().toByteArray())}}}catch(e:Exception){append("Ошибка сохранения: "+e.message)}}}
 override fun onDestroy(){live=false; ready=false; try{socket?.close()}catch(_:Exception){}; worker.shutdownNow(); super.onDestroy()}
}