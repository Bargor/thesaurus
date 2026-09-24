package pl.bargor.thesaurus.di

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import pl.bargor.thesaurus.data.firebase.FirebaseAuthFactory
import pl.bargor.thesaurus.data.firebase.FirebaseFirestoreFactory
import javax.inject.Inject

class FirebaseBackend @Inject constructor() {
    fun auth(): FirebaseAuth = FirebaseAuthFactory.create()
    fun firestore(): FirebaseFirestore = FirebaseFirestoreFactory.create()
}
