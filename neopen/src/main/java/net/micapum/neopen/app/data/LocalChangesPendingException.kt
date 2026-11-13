package net.micapum.neopen.app.data

/** El delta debe esperar al siguiente push, conservando la edición optimista. */
internal class LocalChangesPendingException : IllegalStateException("Hay cambios locales pendientes")
