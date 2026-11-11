package ru.liftzip.orders.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.liftzip.orders.app.ui.OrdersApp
import ru.liftzip.orders.app.ui.OrdersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as OrdersApplication).container
        setContent {
            OrdersTheme {
                OrdersApp(container)
            }
        }
    }
}
