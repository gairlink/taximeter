package com.example.taximeter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class TariffField(val key: String, val label: String)

/** Bottom sheet with the tariff and fuel settings. Edits stay as text until saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TariffSheet(
    current: Tariffs,
    onDismiss: () -> Unit,
    onSave: (Tariffs) -> Unit
) {
    // FIX 1: remember(current) - before it was remember { } without a key,
    // so reopening the sheet after tariffs changed showed OLD values.
    val texts = remember(current) { mutableStateMapOf<String, String>().apply { putAll(current.toMap()) } }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    val fuelPerKm = parseEur(texts["fuelLitersPer100Km"], 0.0) / 100.0 * parseEur(texts["fuelPricePerLiter"], 0.0)

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = sheetState,
        containerColor = CardBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("€ ტარიფები • საწვავი", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Yellow),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ⓘ", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(8.dp))
                IconTile("✕", onClick = close)
            }

            // FIX: typo "წევთოვანი" -> "სტანდარტული"
            TariffSection("📊 სტანდარტული", Tile, Color.White) {
                TariffGroup(texts, listOf(
                    TariffField("km0to5", "0-5კმ €/კმ"),
                    TariffField("km5to15", "5-15კმ €/კმ"),
                    TariffField("km15plus", "15+კმ €/კმ"),
                    TariffField("waitPerMinute", "ლოდინი €/წთ"),
                    TariffField("boarding", "ჩაჯდომა €"),
                    TariffField("freeWaitMinutes", "უფასო ლოდინი წთ")
                ))
            }

            TariffSection("👥 ახლობლები", Tile, Color.White) {
                TariffGroup(texts, listOf(
                    TariffField("friendPerKm", "1 კმ €"),
                    TariffField("friendWaitPerMinute", "ლოდინი €/წთ"),
                    TariffField("friendBoarding", "ჩაჯდომა €")
                ))
            }

            TariffSection("🚗 კლიენტამდე ტარიფი", BlueBg, Blue) {
                TariffGroup(texts, listOf(
                    TariffField("pickupPerKm", "€/კმ"),
                    TariffField("pickupWaitPerMinute", "ლოდინი €/წთ")
                ))
            }

            TariffSection("⛽ საწვავი", Tile, Color.White) {
                TariffGroup(texts, listOf(
                    TariffField("fuelLitersPer100Km", "ლ/100კმ"),
                    TariffField("fuelPricePerLiter", "€/ლ")
                ))
                Text(
                    "%s ლ/100კმ • €%s/ლ = %.3f€/კმ".format(
                        texts["fuelLitersPer100Km"].orEmpty(),
                        texts["fuelPricePerLiter"].orEmpty(),
                        fuelPerKm
                    ),
                    color = Muted,
                    fontSize = 12.sp
                )
            }

            Button(
                // FIX 3: after save the sheet now closes itself.
                // Before, onSave() was called but the sheet stayed open
                // unless the parent dismissed it.
                onClick = {
                    onSave(tariffsFromMap(texts, current))
                    close()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFF8D6), contentColor = Color.Black)
            ) {
                Text("✓ შენახვა", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
internal fun TariffSection(
    title: String,
    background: Color,
    titleColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = titleColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        content()
    }
}

/** Lays out the fields three per row. */
@Composable
private fun TariffGroup(texts: MutableMap<String, String>, fields: List<TariffField>) {
    fields.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { field -> TariffInput(field, texts, Modifier.weight(1f)) }
            // A short last row keeps the same column widths: fill the empty slots.
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun TariffInput(field: TariffField, texts: MutableMap<String, String>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // One line, so a narrow (1/3 width) column stays the same height as the others.
        // Long labels like "უფასო ლოდინი წთ" are cut with ... instead of wrapping to 2 lines.
        Text(field.label, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        OutlinedTextField(
            value = texts[field.key].orEmpty(),
            onValueChange = { texts[field.key] = it },
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            shape = RoundedCornerShape(12.dp),
            // FIX: normal height (56dp is the standard Material text field height).
            // Before it was 44dp, which squeezed the text vertically.
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = fieldColors()
        )
    }
}
