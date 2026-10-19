package com.starsea.nautilus;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;

/** Local UI language only. User messages, drafts and model output are never translated. */
final class I18n {
    enum Language {
        ZH_CN("zh-CN", "🇨🇳", "简体中文"), ZH_HK("zh-HK", "🇭🇰", "繁體中文"),
        HU("hu", "🇭🇺", "Magyarország"), EN("en", "🇺🇸", "English");
        final String code, flag, label;
        Language(String code, String flag, String label) { this.code=code; this.flag=flag; this.label=label; }
    }
    private static final String[][] ROWS = {
        {"联网搜索", "聯網搜尋", "Webes keresés", "Web search"},
        {"联网搜索已开启", "聯網搜尋已開啟", "Webes keresés bekapcsolva", "Web search on"},
        {"联网搜索已关闭", "聯網搜尋已關閉", "Webes keresés kikapcsolva", "Web search off"},
        {"正在切换联网搜索…", "正在切換聯網搜尋…", "Webes keresés váltása…", "Updating web search…"},
        {"切换联网搜索失败，请重试", "切換聯網搜尋失敗，請重試", "Nem sikerült váltani; próbáld újra", "Could not update web search; try again"},
        {"选择模型后可使用联网搜索", "選擇模型後可使用聯網搜尋", "Modellválasztás után elérhető a webes keresés", "Choose a model to use web search"},
        {"回到最新 ↓", "回到最新 ↓", "Legújabb ↓", "Latest ↓"},
        {"历史对话", "歷史對話", "Beszélgetések", "Chat history"},
        {"暂无历史对话", "暫無歷史對話", "Nincs előzmény", "No conversations yet"},
        {"新对话", "新對話", "Új beszélgetés", "New chat"},
        {"删除此对话", "刪除此對話", "Beszélgetés törlése", "Delete conversation"},
        {"已折叠", "已摺疊", "Összecsukva", "Collapsed"},
        {"已展开", "已展開", "Kibontva", "Expanded"},
        {"正在恢复对话…", "正在恢復對話…", "Beszélgetés betöltése…", "Restoring conversation…"},
        {"历史对话已恢复", "歷史對話已恢復", "Beszélgetés visszaállítva", "Conversation restored"},
        {"历史已打开，请选择可用模型继续", "歷史已開啟，請選擇可用模型繼續", "Válassz elérhető modellt a folytatáshoz", "History opened; choose an available model to continue"},
        {"聊天记录保存失败", "聊天記錄儲存失敗", "Az előzmények mentése sikertelen", "Failed to save conversation"},
        {"读取历史对话失败", "讀取歷史對話失敗", "Az előzmények nem olvashatók", "Failed to load conversations"},
        {"正在删除对话…", "正在刪除對話…", "Beszélgetés törlése…", "Deleting conversation…"},
        {"对话已删除", "對話已刪除", "Beszélgetés törölve", "Conversation deleted"},
        {"拖动调整侧边栏；双击展开或收起", "拖曳調整側邊欄；按兩下展開或收起", "Húzással méretezés; dupla kattintással váltás", "Drag to resize; double-click to expand or collapse"},
        {"主题", "主題", "Téma", "Theme"},
        {"明亮", "明亮", "Világos", "Light"},
        {"黑暗", "黑暗", "Sötét", "Dark"},
        {"随系统", "隨系統", "Rendszer", "System"},
        {"无法保存主题设置", "無法儲存主題設定", "A téma nem menthető", "Unable to save theme preference"},
        {"推荐", "推薦", "Javaslatok", "Recommend"},
        {"专家团队", "專家團隊", "Szakértői csapat", "Expert team"},
        {"推荐团队", "推薦團隊", "Ajánlott csapat", "Recommended team"},
        {"优先能力，适合复杂任务", "優先能力，適合複雜任務", "Összetett feladatokra", "Capability for complex tasks"},
        {"兼顾效果与成本，适合日常任务", "兼顧效果與成本，適合日常任務", "Hatékony mindennapi munkára", "Balanced for everyday tasks"},
        {"已填入草稿，可继续拖拽调整", "已填入草稿，可繼續拖曳調整", "Vázlatba töltve; húzással módosítható", "Draft filled; drag to adjust"},
        {"仅使用已配置密钥的平台；应用后仍可拖拽调整", "僅使用已設定密鑰的平台；套用後仍可拖曳調整", "Csak beállított szolgáltatókat használ; továbbra is szerkeszthető", "Uses configured providers only; assignments remain editable"},
        {"展开团队推荐", "展開團隊推薦", "Csapatjavaslatok megnyitása", "Show team recommendations"},
        {"收起团队推荐", "收起團隊推薦", "Csapatjavaslatok bezárása", "Hide team recommendations"},
        {"同一平台一个子模型；规划最多一个，汇总需一个。", "同一平台一個子模型；規劃最多一個，彙總需一個。", "Szolgáltatónként egy modell; tervező opcionális, egy összegző szükséges.", "One model per provider; Planning optional, one Synthesis model required."},
        {"拖动模型，组建你的协作团队", "拖動模型，組建你的協作團隊", "Húzd a modelleket a csapatodba", "Drag models to build your team"},
        {"拖到角色区分配职责，拖回模型列移除。", "拖至角色區分配職責，拖回模型欄移除。", "Húzd egy szerephez; visszahúzással eltávolíthatod.", "Drop onto a role to assign; drag back to remove."},
        {"直接拖动任意子模型", "直接拖動任意子模型", "Húzd a kiválasztott modellt", "Drag any model variant"},
        {"模型", "模型", "Modellek", "Models"},
        {"角色", "角色", "Szerepek", "Roles"},
        {"将至少一个模型拖到汇总区开始组队", "將至少一個模型拖至彙總區開始組隊", "Kezdésként húzz egy modellt az Összegzéshez", "Start by dropping a model into Synthesis"},
        {"请将一个模型拖到汇总区", "請將一個模型拖至彙總區", "Húzz egy modellt az Összegzéshez", "Drop a model into Synthesis"},
        {"同一平台保留一个子模型；规划和汇总各一个，其余可留空。", "同一平台保留一個子模型；規劃與彙總各一個，其餘可留空。", "Szolgáltatónként egy modell; egy tervező és egy összegző fér el.", "One model per provider; Planning and Synthesis each hold one."},
        {"拖动模型到这里", "拖動模型至這裡", "Húzz ide egy modellt", "Drop a model here"},
        {"拖动分配角色", "拖動分配角色", "Húzd egy szerephez", "Drag to assign a role"},
        {"拖拽分工", "拖放分工", "Szerepek húzással", "Drag-to-assign roles"},
        {"已移回模型列", "已移回模型欄", "Visszahelyezve a modellekhez", "Returned to Models"},
        {"清空分配", "清空分配", "Kiosztások törlése", "Clear assignments"},
        {"已分配", "已分配", "Kiosztva", "Assigned"},
        {"未分配", "未分配", "Nincs kiosztva", "Unassigned"},
        {"移除", "移除", "Eltávolítás", "Remove"},
        {"选中模型后点击分配；也可按数字键 1 至 4", "選取模型後按一下分配；亦可按數字鍵 1 至 4", "Válassz modellt, majd szerepet, vagy használd az 1–4 billentyűket", "Select a model, then click a role or press 1–4"},
        {"拖动分配角色；按 1 / 2 / 3 / 4 分配，Delete 移除", "拖動分配角色；按 1 / 2 / 3 / 4 分配，Delete 移除", "Húzd a szerephez; 1 / 2 / 3 / 4: kiosztás, Delete: eltávolítás", "Drag to a role; 1 / 2 / 3 / 4 to assign, Delete to remove"},
        {"顺序需为 1 至 4 的整数", "順序需為 1 至 4 的整數", "A sorrend 1 és 4 közötti egész szám", "Order must be an integer from 1 to 4"},
        {"规划", "規劃", "Tervezés", "Planning"},
        {"执行", "執行", "Végrehajtás", "Execution"},
        {"复核", "覆核", "Ellenőrzés", "Review"},
        {"汇总", "彙總", "Összegzés", "Synthesis"},
        {"复核中", "覆核中", "Ellenőrzés", "Reviewing"},
        {"执行与复核", "執行與覆核", "Végrehajtás és ellenőrzés", "Execution & review"},
        {"执行分配的子任务，补充实现与验证建议。", "執行分配的子任務，補充實作與驗證建議。", "Részfeladatok végrehajtása és ellenőrzési javaslatok.", "Complete assigned subtasks and add implementation and validation advice."},
        {"选择模型，自定义角色与目标，再确认协作流程。", "選擇模型，自訂角色與目標，再確認協作流程。", "Válassz modelleket, szerepeket és célokat, majd erősítsd meg.", "Choose models, define roles and goals, then confirm your workflow."},
        {"协作阶段", "協作階段", "Szakasz", "Stage"},
        {"阶段内顺序", "階段內順序", "Sorrend", "Stage order"},
        {"角色名称", "角色名稱", "Szerep neve", "Role name"},
        {"具体目标 / 验收条件", "具體目標 / 驗收條件", "Cél / Elfogadási feltételek", "Goal / Acceptance criteria"},
        {"允许所选成员跨职责接替失败任务", "允許所選成員跨職責接替失敗任務", "Más szerepű tagok átvehetik a sikertelen feladatokat", "Allow selected members to take over tasks across roles"},
        {"可不设规划成员；需一个汇总成员。默认不跨职责接替。", "可不設規劃成員；需一個彙總成員。預設不跨職責接替。", "Tervező opcionális, egy összegző szükséges. Alapból nincs szerepváltás.", "Planner optional; one summarizer required. Cross-role fallback is off by default."},
        {"请指定且仅指定一个最终汇总成员", "請指定且僅指定一個最終彙總成員", "Pontosan egy összegző tagot válassz", "Assign exactly one final summarizer"},
        {"任务规划成员最多一个", "任務規劃成員最多一個", "Legfeljebb egy tervező választható", "Assign at most one planner"},
        {"角色名称需为 1 至 64 个字符，且不能包含换行或控制字符", "角色名稱需為 1 至 64 個字元，且不可包含換行或控制字元", "A szerep neve 1–64 karakter, vezérlőkarakter nélkül", "Role names need 1–64 characters without line breaks or control characters"},
        {"每个成员的目标需为 1 至 2000 个字符", "每個成員的目標需為 1 至 2000 個字元", "Minden cél 1–2000 karakter lehet", "Each member needs a goal of 1–2000 characters"},
        {"自定义分工", "自訂分工", "Egyéni szerepek", "Custom roles"},
        {"使用建议分工", "使用建議分工", "Javasolt szerepek", "Use suggested roles"},
        {"正在按指定目标规划任务", "正在按指定目標規劃任務", "Tervezés a megadott cél szerint", "Planning for the assigned goal"},
        {"正在完成指定角色目标", "正在完成指定角色目標", "A megadott szerep céljának teljesítése", "Working toward the assigned role goal"},
        {"正在按指定目标汇总最终产物", "正在按指定目標彙總最終產物", "Véglegesítés a megadott cél szerint", "Finalizing for the assigned goal"},
        {" 模型选择，", " 模型選擇，", " modellválasztás, ", " model selection, "},
        {"长任务与代码", "長任務與程式碼", "Hosszú feladatok és kód", "Long tasks & coding"},
        {"复杂推理", "複雜推理", "Összetett érvelés", "Complex reasoning"},
        {"均衡对话与协作", "均衡對話與協作", "Kiegyensúlyozott csapatmunka", "Balanced chat & teamwork"},
        {"快速响应", "快速回應", "Gyors válaszok", "Fast responses"},
        {"核实多方意见，补充依据与验证建议。", "核實各方意見，補充依據與驗證建議。", "Vélemények ellenőrzése és ellenőrzési javaslatok.", "Verify contributions and add evidence and checks."},
        {"正在复核并完善方案","正在覆核並完善方案","Terv ellenőrzése és javítása","Reviewing and improving the plan"},
        {"需求分析与方案设计","需求分析與方案設計","Igények elemzése és tervezés","Analyzing requirements and planning"},
        {"已复制 ✓","已複製 ✓","Másolva ✓","Copied ✓"},
        {"实时协作进度","即時協作進度","Élő csapatfolyamat","Live collaboration progress"},
        {"思考中💭","思考中💭","Gondolkodás 💭","Thinking 💭"},
        {"生成中","產生中","Generálás","Generating"},
        {"总结中","總結中","Összegzés","Summarizing"},
        {"已完成","已完成","Kész","Completed"},
        {"未完成","未完成","Befejezetlen","Incomplete"},
        {"耗时: ","耗時: ","Idő: ","Elapsed: "},
        {"已将任务拆分为 ","已將任務拆分為 ","A feladat felbontva: ","Task split into "},
        {" 个子节点"," 個子節點"," részfeladat"," subtasks"},
        {"阶段已返回结果","階段已回傳結果","A szakasz eredménye elkészült","Stage returned a result"},
        {"正在生成回复","正在產生回覆","Válasz készítése","Generating a reply"},
        {"回复已生成","回覆已產生","Válasz elkészült","Reply generated"},
        {"模型请求失败","模型請求失敗","Sikertelen modellkérés","Model request failed"},
        {"正在汇总最终产物","正在彙整最終產物","Végleges eredmény összegzése","Summarizing the final result"},
        {"模型不可用，转交剩余模型","模型無法使用，轉交其餘模型","A modell nem érhető el; átadás","Model unavailable; handing off"},
        {"阶段未完成","階段未完成","Befejezetlen szakasz","Stage incomplete"},
        {"方案已生成，未返回有效的结构化子节点","方案已產生，未回傳有效的結構化子節點","Terv elkészült; nincs érvényes részfeladatlista","Plan returned without a valid structured task list"},
        {"正在处理子节点 ","正在處理子節點 ","Részfeladat feldolgozása: ","Working on subtask "},
        {"子模型","子模型","Almodell","Submodel"},
        {"扩展思考","延伸思考","Bővített gondolkodás","Extended thinking"},
        {"深度思考","深度思考","Mély gondolkodás","Deep thinking"},
        {"Mixture模式下，突然更换到独立模型，上下文记忆暂时无法生效。","Mixture模式下，突然切換至獨立模型，對話內容記憶暫時無法生效。","Mixture módból önálló modellre váltva a beszélgetés előzményei egyelőre nem vihetők át.","When switching from Mixture to a standalone model, conversation context cannot currently carry over."},
        {" 模型选择，"," 模型選擇，"," modellválasztás, "," model selection, "},
        {"Flash 模型","Flash 模型","Flash modell","Flash model"},
        {"Pro 模型","Pro 模型","Pro modell","Pro model"},
        {"文字对话","文字對話","SZÖVEGES BESZÉLGETÉSEK","TEXT CONVERSATIONS"},
        {"好奇心，让想法走得更远","好奇心，讓想法走得更遠","A KÍVÁNCSISÁG MESSZIRE VISZ","A LITTLE CURIOSITY GOES A LONG WAY"},
        {"本次附带 ","本次附加 ","Csatolva: ","Including "},
        {" 的当前代码与批注"," 的目前程式碼與註解"," aktuális kódja és megjegyzései"," with current code and annotations"},
        {"（长内容已截断）","（長內容已截短）"," (hosszú tartalom rövidítve)"," (long content truncated)"},
        {"已修改 · 原回复保留。 ","已修改 · 保留原回覆。 ","Szerkesztve · Eredeti válasz megmarad. ","Edited · Original reply retained. "},
        {"批注：","註解：","Megjegyzés: ","Annotation: "},
        {"设置", "設定", "Beállítások", "Settings"},
        {"API 密钥与隐私", "API 金鑰與私隱", "API-kulcsok és adatvédelem", "API keys & privacy"},
        {"语言", "語言", "Nyelv", "Language"},
        {"退出", "結束", "Kilépés", "Exit"},
        {"语言偏好保存失败，本次切换仍有效", "語言偏好儲存失敗，本次切換仍有效", "A nyelv mentése sikertelen; a váltás most érvényes", "Could not save language; this change still applies"},
        {"让想法自由流动", "讓想法自由流動", "Engedd szabadjára az ötleteid", "Let ideas flow"},
        {"＋  新对话", "＋  新對話", "＋  Új beszélgetés", "＋  New chat"},
        {"模型空间", "模型空間", "Modellek", "Models"},
        {"开始一段新对话", "開始一段新對話", "Kezdj új beszélgetést", "Start a new conversation"},
        {"选择模型，带着你的问题出发", "選擇模型，帶着你的問題出發", "Válassz modellt, és tedd fel a kérdésed", "Choose a model and ask a question"},
        {"正在准备…", "正在準備…", "Előkészítés…", "Getting ready…"},
        {"●  纯文本对话", "●  純文字對話", "●  Szöveges csevegés", "●  Text chat"},
        {"把想法，聊清楚。", "把想法，聊清楚。", "Beszéljük át az ötleteidet.", "Talk your ideas through."},
        {"从一个问题开始，让思路慢慢展开。", "從一個問題開始，讓思路慢慢展開。", "Kezdd egy kérdéssel, és bontsd ki a gondolataid.", "Start with a question. See where it leads."},
        {"读懂一段代码", "讀懂一段程式碼", "Kód megértése", "Understand code"},
        {"梳理一个想法", "梳理一個想法", "Ötlet kidolgozása", "Explore an idea"},
        {"制定学习计划", "制定學習計劃", "Tanulási terv", "Make a study plan"},
        {"请帮我解释下面这段代码的逻辑：", "請幫我解釋以下程式碼的邏輯：", "Kérlek, magyarázd el az alábbi kód logikáját:", "Please explain the logic of this code:"},
        {"我想和你一起梳理一个想法：", "我想和你一起梳理一個想法：", "Szeretnék közösen kidolgozni egy ötletet:", "I'd like to explore this idea with you:"},
        {"请帮我制定一份学习计划：", "請幫我制定一份學習計劃：", "Kérlek, készíts egy tanulási tervet:", "Please help me create a study plan:"},
        {"发送  ↑", "傳送  ↑", "Küldés  ↑", "Send  ↑"},
        {"附带", "附加", "Csatolás", "Include"},
        {"填入建议", "填入建議", "Javaslat beillesztése", "Use suggestion"},
        {"关闭", "關閉", "Bezárás", "Close"},
        {"Enter 发送 · Shift + Enter 换行", "Enter 傳送 · Shift + Enter 換行", "Enter: küldés · Shift + Enter: új sor", "Enter to send · Shift + Enter for a new line"},
        {"向 Nautilus 发送消息…", "向 Nautilus 傳送訊息…", "Üzenet a Nautilusnak…", "Message Nautilus…"},
        {"Mixture 调度", "Mixture 調度", "Mixture folyamat", "Mixture progress"},
        {"正在准备模型…", "正在準備模型…", "Modellek előkészítése…", "Preparing models…"},
        {"请在 API 密钥与隐私中配置一个平台", "請在 API 金鑰與私隱中設定一個平台", "Állíts be egy szolgáltatót az API-kulcsoknál", "Configure a provider in API keys & privacy"},
        {"正在读取密钥状态…", "正在讀取金鑰狀態…", "Kulcsállapot betöltése…", "Loading key status…"},
        {"密钥配置已更新，旧会话已清空", "金鑰設定已更新，舊對話已清空", "Kulcsok frissítve; előző beszélgetés törölve", "Keys updated; previous conversation cleared"},
        {"配置已更新；关闭此窗口后即可使用已配置的模型。", "設定已更新；關閉此視窗後即可使用已設定的模型。", "Beállítások frissítve. Az ablak bezárása után használhatod a modelleket.", "Settings updated. Close this window to use your models."},
        {"操作被中断，请重试", "操作被中斷，請重試", "A művelet megszakadt. Próbáld újra.", "Operation interrupted. Please retry."},
        {"操作失败，请重试", "操作失敗，請重試", "Sikertelen művelet. Próbáld újra.", "Operation failed. Please retry."},
        {"正在组建协作团队…", "正在組建協作團隊…", "Csapat összeállítása…", "Assembling your team…"},
        {"正在切换模型…", "正在切換模型…", "Modellváltás…", "Switching models…"},
        {"思考模式 + Google Search · 切换时保留上下文", "思考模式 + Google Search · 切換時保留上下文", "Gondolkodás + Google Search · Az előzmények megmaradnak", "Thinking + Google Search · Context retained on switch"},
        {" · 与单模型会话独立", " · 與單模型對話獨立", " · Külön csapatbeszélgetés", " · Separate team conversation"},
        {" · 切换时保留上下文", " · 切換時保留上下文", " · Az előzmények megmaradnak", " · Context retained on switch"},
        {"就绪", "就緒", "Kész", "Ready"},
        {"你 · 附带代码批注", "你 · 附加程式碼註解", "Te · Kódmegjegyzésekkel", "You · With code annotations"},
        {"你", "你", "Te", "You"},
        {"正在思考…", "正在思考…", "Gondolkodás…", "Thinking…"},
        {"正在新建对话…", "正在建立新對話…", "Új beszélgetés indítása…", "Starting a new chat…"},
        {"新对话已就绪", "新對話已就緒", "Az új beszélgetés kész", "New chat ready"},
        {"请求未完成 · 可以继续重试或切换模型", "請求未完成 · 可重試或切換模型", "Sikertelen kérés · Próbáld újra vagy válts modellt", "Request incomplete · Retry or switch models"},
        {"请求未完成", "請求未完成", "Sikertelen kérés", "Request incomplete"},
        {"基于点赞的后续建议（可编辑）：", "根據讚好產生的後續建議（可編輯）：", "Javasolt folytatás a kedvelés alapján (szerkeszthető):", "Suggested follow-up based on your like (editable):"},
        {"本次不附带代码批注", "本次不附加程式碼註解", "Kódmegjegyzések nélkül", "Code annotations excluded"},
        {"通用对话", "一般對話", "Általános csevegés", "General chat"},
        {"Mixture混合模式", "Mixture混合模式", "Mixture csapatmód", "Mixture team mode"},
        {"自由组队 · 分工协作", "自由組隊 · 分工協作", "Saját csapat · Közös munka", "Your team · Shared work"},
        {"深度思考 · Search", "深度思考 · Search", "Mély gondolkodás · Search", "Deep thinking · Search"},
        {"根据任务选择 Flash / Argon", "按任務選擇 Flash / Argon", "Flash / Argon feladat szerint", "Flash / Argon by task"},
        {"按任务选择 Flash / Argon", "按任務選擇 Flash / Argon", "Flash / Argon feladat szerint", "Flash / Argon by task"},
        {"扩展思考 · Search", "延伸思考 · Search", "Bővített gondolkodás · Search", "Extended thinking · Search"},
        {"自动", "自動", "Automatikus", "Auto"},
        {"正在处理…", "正在處理…", "Feldolgozás…", "Working…"},
        {"未配置", "未設定", "Nincs beállítva", "Not configured"},
        {"Gemini 模型选择，", "Gemini 模型選擇，", "Gemini modellválasztás, ", "Gemini model selection, "},
        {"，当前使用", "，目前使用", ", jelenleg aktív", ", currently active"},
        {"已展开；上下方向键选择，Enter 确认，Escape 收起", "已展開；上下方向鍵選擇，Enter 確認，Escape 收起", "Nyitva; nyilak: választás, Enter: megerősítés, Escape: bezárás", "Expanded; arrows to choose, Enter to confirm, Escape to close"},
        {"点击或按 Enter 展开模型分支", "按一下或按 Enter 展開模型選項", "Kattints vagy nyomj Entert a modellekhez", "Click or press Enter to show models"},
        {" · 点击", " · 按一下", " · Kattints: ", " · Click to "},
        {"收起", "收起", "bezárás", "collapse"},
        {"选择分支", "選擇選項", "modellválasztás", "choose a model"},
        {"我已阅读上述说明，并选择将输入的密钥明文保存在本机账号下", "我已閱讀上述說明，並選擇將輸入的金鑰以明文儲存在本機帳號下", "Elolvastam a tájékoztatót; a kulcsokat titkosítás nélkül mentem a helyi fiókba", "I have read the notice and choose to save keys as plaintext in my local account"},
        {"保存密钥", "儲存金鑰", "Kulcsok mentése", "Save keys"},
        {"删除全部本机密钥", "刪除全部本機金鑰", "Összes helyi kulcs törlése", "Delete all local keys"},
        {"完成 / 稍后设置", "完成 / 稍後設定", "Kész / Később", "Done / Set up later"},
        {"留空表示保持已保存的密钥不变；只需填写需要使用的平台。", "留空表示保留已儲存的金鑰；只需填寫要使用的平台。", "Az üres mezők nem módosítják a mentett kulcsokat.", "Leave blank to keep saved keys. Fill in only the providers you need."},
        {"你的模型，你的 API key", "你的模型，你的 API key", "Saját modellek, saját API-kulcsok", "Your models, your API keys"},
        {"密钥仅由本机 Rust 服务用于对应平台的模型调用", "金鑰僅由本機 Rust 服務用於對應平台的模型呼叫", "A kulcsokat csak a helyi Rust szolgáltatás használja az adott szolgáltatónál", "Only the local Rust service uses keys to call the corresponding provider"},
        {"删除", "刪除", "Törlés", "Delete"},
        {"明文存储与隐私说明", "明文儲存與私隱說明", "Titkosítatlan tárolás és adatvédelem", "Plaintext storage & privacy"},
        {"• 密钥不加密保存在下方 .env；同一系统账号下的程序或管理员仍可能读取。文件权限限制为当前用户，应用不会上传密钥给开发者。", "• 金鑰以未加密形式儲存在下方 .env；同一系統帳號的程式或管理員仍可能讀取。檔案權限限於目前使用者，應用程式不會向開發者上傳金鑰。", "• A kulcsok titkosítás nélkül kerülnek az alábbi .env fájlba. Azonos fiók programjai és rendszergazdák olvashatják. A fájl jogosultsága a jelenlegi felhasználóra korlátozott; az alkalmazás nem küldi a kulcsokat a fejlesztőnek.", "• Keys are stored unencrypted in the .env file below. Programs under the same account or administrators may read them. Permissions restrict access to the current user; the app does not upload keys to the developer."},
        {"• 发送消息时，密钥用于对应平台鉴权；问题与当前会话历史会发送至所选平台。切换平台后，已有上下文也会发送给新平台；Gemini 搜索还会使用 Google Search。相关保存期限、地域和费用由平台条款及你的 API 账号设置决定。", "• 傳送訊息時，金鑰用於平台驗證；問題與目前對話紀錄會傳送至所選平台。切換平台後，現有內容亦會傳送至新平台；Gemini 搜尋亦使用 Google Search。保留期限、地區及費用由平台條款和 API 帳號設定決定。", "• Üzenetküldéskor a kulcs hitelesít, a kérdés és a beszélgetés előzményei a kiválasztott szolgáltatóhoz kerülnek. Váltáskor a korábbi kontextus az új szolgáltatóhoz is eljut; a Gemini keresése Google Search szolgáltatást használ. A megőrzést, régiót és díjakat a szolgáltató feltételei és API-fiókod beállításai határozzák meg.", "• Keys authenticate requests; your question and conversation history are sent to the selected provider. Switching sends existing context to the new provider; Gemini search also uses Google Search. Retention, regions and costs depend on provider terms and your API account settings."},
        {"• 聊天记录、草稿和代码批注自动保存在本机历史对话，可在侧栏删除。密钥保留至你主动删除；删除本机副本不会撤销平台密钥，也不会删除平台已处理的数据或系统备份。", "• 對話記錄、草稿與程式碼批註自動儲存在本機，可在側邊欄刪除。金鑰保留至你主動刪除；刪除本機副本不會撤銷平台金鑰，亦不會刪除平台已處理的資料或系統備份。", "• Az előzményeket, vázlatokat és kódjegyzeteket a gépen menti; az oldalsávból törölhetők. A kulcsokat törlésig megőrzi. A helyi másolat törlése nem vonja vissza a szolgáltatói kulcsot, és nem törli a feldolgozott adatokat vagy rendszermentéseket.", "• Chats, drafts and code annotations are saved locally and can be deleted in the sidebar. Keys remain until you delete them. Deleting local copies does not revoke provider keys or delete processed data or system backups."},
        {"• 保存或删除密钥将开始新对话，避免在不同凭据之间继续发送旧历史。这是本机账号配置，不是云端账号登录。", "• 儲存或刪除金鑰會開始新對話，以免在不同憑證間傳送舊紀錄。這是本機帳號設定，並非雲端登入。", "• Kulcsok mentése vagy törlése új beszélgetést indít, így a régi előzmények nem kerülnek más hitelesítő adatokhoz. Ez helyi beállítás, nem felhőbejelentkezés.", "• Saving or deleting keys starts a new chat to prevent sending old history with different credentials. This configures your local account; it is not a cloud login."},
        {"当前本机账号：", "目前本機帳號：", "Helyi fiók: ", "Local account: "},
        {"存储位置：", "儲存位置：", "Tárolási hely: ", "Storage location: "},
        {"已保存 · 留空保持不变", "已儲存 · 留空保留", "Mentve · Üresen nem változik", "Saved · Leave blank to keep"},
        {"复制全文", "複製全文", "Teljes szöveg másolása", "Copy all"},
        {"复制原始 Markdown 全文；代码块按钮复制当前编辑版本", "複製原始 Markdown 全文；程式碼區塊按鈕複製目前編輯版本", "Az eredeti Markdown másolása; a kódgombok a szerkesztett verziót másolják", "Copy original Markdown; code buttons copy the edited version"},
        {"点赞", "讚好", "Tetszik", "Like"},
        {"已点赞 ✓", "已讚好 ✓", "Kedvelve ✓", "Liked ✓"},
        {"作为本轮偏好，生成可编辑的后续问题建议；不会自动发送", "作為本輪偏好，產生可編輯的後續問題建議；不會自動傳送", "Szerkeszthető folytatást javasol; nem küldi el automatikusan", "Suggest an editable follow-up; never sends automatically"},
        {"请沿着刚才的思路，给出一个具体示例，并说明注意事项。", "請沿着剛才的思路，給一個具體例子，並說明注意事項。", "Adj egy konkrét példát az előző gondolatmenethez, és emeld ki a fontos szempontokat.", "Give a concrete example following that idea and explain what to watch out for."},
        {"请结合 ", "請結合 ", "Vedd figyelembe: ", "Please use "},
        {" 的代码和批注，解释关键实现，并补充边界情况与测试示例。", " 的程式碼和註解，解釋關鍵實作，並補充邊界情況與測試例子。", " kódját és megjegyzéseit; magyarázd el a megvalósítást, szélső eseteket és teszteket.", " and its code and annotations to explain the implementation, edge cases and tests."},
        {"已生成下一句建议 · 点击输入区的建议后可编辑", "已產生下一句建議 · 按輸入區建議後可編輯", "Folytatás elkészült · Beillesztés után szerkeszthető", "Follow-up ready · Insert it to edit"},
        {"已取消点赞", "已取消讚好", "Kedvelés visszavonva", "Like removed"},
        {"已复制到剪贴板", "已複製到剪貼簿", "Vágólapra másolva", "Copied to clipboard"},
        {"剪贴板不可用，请选中文本后复制", "剪貼簿無法使用，請選取文字後複製", "A vágólap nem érhető el; jelöld ki és másold a szöveget", "Clipboard unavailable; select and copy the text"},
        {"复制", "複製", "Másolás", "Copy"},
        {"修改", "修改", "Szerkesztés", "Edit"},
        {"批注", "註解", "Megjegyzés", "Annotate"},
        {"保存", "儲存", "Mentés", "Save"},
        {"恢复原代码并清空批注", "還原原始程式碼並清空註解", "Eredeti kód visszaállítása, megjegyzések törlése", "Restore original code and clear annotations"},
        {"取消", "取消", "Mégse", "Cancel"},
        {" 已保存 · 相关追问时附带；也可直接在问题中写 ", " 已儲存 · 相關追問時附加；亦可在問題中寫 ", " mentve · Kapcsolódó kérdéshez csatolva; hivatkozz rá: ", " saved · Included in related follow-ups; you can reference "},
        {" 已恢复原始版本", " 已還原原始版本", " visszaállítva", " restored to original"},
        {" · 本地版本（原回复保留）", " · 本機版本（保留原回覆）", " · Helyi verzió (eredeti válasz megmarad)", " · Local version (original reply retained)"},
        {"批注 · 可注明行号、问题和修改意图", "註解 · 可註明行號、問題與修改意圖", "Megjegyzés · Sorok, kérdések, módosítási cél", "Annotations · Include line numbers, questions and intent"},
        {"引用所选代码行", "引用所選程式碼行", "Kijelölt sorok idézése", "Quote selected lines"},
        {"第 ", "第 ", "Sor: ", "Line "},
        {" 行：", " 行：", ": ", ": "},
        {"相关追问会附带当前代码与批注；恢复原代码并清空批注可取消自动增强。", "相關追問會附加目前程式碼與註解；還原原始程式碼並清空註解可取消自動補充。", "A kapcsolódó kérdésekhez csatoljuk a kódot és megjegyzéseket. Visszaállítással megszüntethető.", "Related follow-ups include current code and annotations. Restore the original to stop including them."},
        {"系统无法打开浏览器", "系統無法開啟瀏覽器", "A böngésző nem nyitható meg", "Cannot open browser"},
        {"无法打开浏览器，请复制链接地址：", "無法開啟瀏覽器，請複製連結：", "A böngésző nem nyitható meg; másold a linket:", "Cannot open browser; copy this link:"},
        {"打开链接", "開啟連結", "Hivatkozás megnyitása", "Open link"},
        {"开始协作", "開始協作", "Együttműködés indítása", "Start collaborating"},
        {"MIXTURE  /  实验性协作", "MIXTURE  /  實驗性協作", "MIXTURE  /  Kísérleti csapatmunka", "MIXTURE  /  Experimental collaboration"},
        {"为这次任务，组建你的模型团队", "為這次任務，組建你的模型團隊", "Állítsd össze a modellcsapatod", "Build your model team"},
        {"选择参与者，查看职责，再由团队交接并产出最终结果。", "選擇參與者，查看職責，再由團隊交接並產出最終結果。", "Válassz résztvevőket és szerepeket a közös eredményhez.", "Choose participants, review roles, and let the team deliver."},
        {"方案设计", "方案設計", "Tervezés", "Planning"},
        {"拆解需求与约束，形成可执行方案或初稿。", "拆解需求與限制，形成可執行方案或初稿。", "Igények és korlátok elemzése, terv vagy vázlat készítése.", "Analyze requirements and constraints; draft a plan or solution."},
        {"独立复核", "獨立覆核", "Ellenőrzés", "Review"},
        {"检查漏洞、边界与分歧，补充修正和验证建议。", "檢查漏洞、邊界與分歧，補充修正和驗證建議。", "Hibák, szélső esetek és eltérések ellenőrzése, javítási javaslatok.", "Check gaps, edge cases and disagreements; propose fixes."},
        {"综合交付", "綜合交付", "Összegzés", "Delivery"},
        {"核实多方意见，整合成完整答案、代码或文稿。", "核實各方意見，整合成完整答案、程式碼或文稿。", "Vélemények ellenőrzése, teljes válasz, kód vagy szöveg készítése.", "Verify contributions; deliver a complete answer, code or draft."},
        {"测试版暂不启用预算限制 · 角色是协作分工，不是能力排名", "測試版暫不啟用預算限制 · 角色為協作分工，非能力排名", "Tesztverzió: nincs költségkeret · A szerepek nem rangsorok", "Test release: no budget limits · Roles are not ability rankings"},
        {"未参与", "未參與", "Nem vesz részt", "Not participating"},
        {"独立完成 · 最终交付", "獨立完成 · 最終交付", "Önálló munka · Átadás", "Solo · Final delivery"},
        {"至少选择一个已配置密钥的模型", "至少選擇一個已設定金鑰的模型", "Válassz legalább egy beállított modellt", "Select at least one configured model"},
        {"已选择 ", "已選擇 ", "Kiválasztva: ", "Selected: "},
        {" 个模型 · ", " 個模型 · ", " modell · ", " models · "},
        {"独立交付", "獨立交付", "Önálló munka", "Solo delivery"},
        {"接力协作", "接力協作", "Közös munka", "Team handoff"},
        {"未配置的模型可在「API 密钥与隐私」中启用。", "未設定的模型可在「API 金鑰與私隱」中啟用。", "A hiányzó modellek az API-kulcsoknál állíthatók be.", "Enable missing models in API keys & privacy."},
        {"Mixture · 协作团队", "Mixture · 協作團隊", "Mixture · Csapat", "Mixture · Team"},
        {"请先在 API 密钥与隐私中配置此模型", "請先在 API 金鑰與私隱中設定此模型", "Előbb állítsd be a modellt az API-kulcsoknál", "Configure this model in API keys & privacy first"},
        {"，参与协作", "，參與協作", ", csapattag", ", team member"},
        {"，未配置密钥", "，未設定金鑰", ", nincs API-kulcs", ", no API key"},
        {"未配置密钥", "未設定金鑰", "Nincs API-kulcs", "No API key"},
        {"独立完成", "獨立完成", "Önálló munka", "Solo"},
        {"独立分析需求，直接完成最终答案、代码或文稿。", "獨立分析需求，直接完成最終答案、程式碼或文稿。", "Igények önálló elemzése és a végleges eredmény elkészítése.", "Analyze the task independently and deliver the final result."},
        {"核实前序成果并解决分歧，整合为完整的最终产物。", "核實前序成果並解決分歧，整合為完整的最終產物。", "Korábbi eredmények ellenőrzése és egységes véglegesítés.", "Verify prior work, resolve disagreements, and finalize the result."}    };
    private static final Map<String,String[]> translations = new HashMap<>();
    private static final Pattern phrases;
    private static Language current = load();
    static {
        for (String[] row:ROWS) translations.put(row[0],row);
        phrases=Pattern.compile(translations.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")));
    }
    private I18n() {}
    static Language language() { return current; }
    static String t(String source) {
        if(source==null || current==Language.ZH_CN)return source;
        Matcher m=phrases.matcher(source); StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(translations.get(m.group())[current.ordinal()]));
        m.appendTail(out);return out.toString();
    }
    static String translate(String source, Language language) {
        Language previous=current; try { current=language; return t(source); } finally { current=previous; }
    }
    static void change(Language language, Component root) throws IOException {
        current=language; refresh(root);
        for(Window window:Window.getWindows())if(window.isDisplayable())refresh(window);
        Path path=preferencePath(); Files.createDirectories(path.getParent());
        Path temporary=Files.createTempFile(path.getParent(),"language-",".tmp");
        try { Files.writeString(temporary,language.code,StandardCharsets.UTF_8); Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
    }
    static Path preferencePath() {
        String test=System.getProperty("nautilus.language.path"); if(test!=null)return Path.of(test).toAbsolutePath();
        return PlatformPaths.profileDirectory().resolve("language.txt");
    }
    private static Language load() {
        try { String code=Files.readString(preferencePath()).strip(); for(Language l:Language.values())if(l.code.equals(code))return l; }
        catch(IOException | SecurityException ignored) { /* default language */ }
        return Language.ZH_CN;
    }
    static void refresh(Component component) {
        if(component instanceof Localized localized)localized.refreshLanguage();
        if(component instanceof JComponent c && c.getClientProperty("i18n.tooltip") instanceof String s)c.setToolTipText(t(s));
        if(component instanceof Container container)for(Component child:container.getComponents())refresh(child);
        component.invalidate(); if(component instanceof JComponent c)c.revalidate(); component.repaint();
    }
    interface Localized { void refreshLanguage(); }
    @SuppressWarnings("serial")
    static final class Label extends JLabel implements Localized {
        private String source;
        Label(String text) { super(text); }
        @Override public void setText(String text) { source=text; super.setText(t(text)); }
        @Override public void refreshLanguage() { super.setText(t(source)); }
    }
    @SuppressWarnings("serial")
    static final class CheckBox extends JCheckBox implements Localized {
        private String source;
        CheckBox(String text, boolean selected) { super(text,selected); }
        @Override public void setText(String text) { source=text; super.setText(t(text)); }
        @Override public void refreshLanguage() { super.setText(t(source)); }
    }
    static void tooltip(JComponent c,String source) { c.putClientProperty("i18n.tooltip",source);c.setToolTipText(t(source)); }
}
