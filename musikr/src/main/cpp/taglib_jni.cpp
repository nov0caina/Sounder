/*
 * Copyright (c) 2024 Auxio Project
 * taglib_jni.cpp is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
#include <jni.h>
#include <string>
#include "JInputStream.h"
#include "JClassRef.h"
#include "JMetadataBuilder.h"
#include "JObjectRef.h"
#include "util.h"

#include "taglib/fileref.h"
#include "taglib/flacfile.h"
#include "taglib/flacpicture.h"
#include "taglib/mp4file.h"
#include "taglib/mp4properties.h"
#include "taglib/mp4tag.h"
#include "taglib/mp4coverart.h"
#include "taglib/mp4item.h"
#include "taglib/mpegfile.h"
#include "taglib/id3v2tag.h"
#include "taglib/attachedpictureframe.h"
#include "taglib/opusfile.h"
#include "taglib/xiphcomment.h"
#include "taglib/vorbisfile.h"
#include "taglib/wavfile.h"

bool parseMpeg(const std::string &name, TagLib::MPEG::File *mpegFile,
        JMetadataBuilder &jBuilder) {
    auto id3v1Tag = mpegFile->ID3v1Tag();
    if (id3v1Tag != nullptr) {
        try {
            jBuilder.setId3v1(*id3v1Tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse ID3v1 tag in %s: %s", name.c_str(), e.what());
        }
    }
    auto id3v2Tag = mpegFile->ID3v2Tag();
    if (id3v2Tag != nullptr) {
        try {
            jBuilder.setId3v2(*id3v2Tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse ID3v2 tag in %s: %s", name.c_str(), e.what());
        }
    }
    return true;
}

bool parseMp4(const std::string &name, TagLib::MP4::File *mp4File,
        JMetadataBuilder &jBuilder) {
    auto tag = mp4File->tag();
    if (tag != nullptr) {
        try {
            jBuilder.setMp4(*tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse MP4 tag in %s: %s", name.c_str(), e.what());
        }
    }
    return true;
}

bool parseFlac(const std::string &name, TagLib::FLAC::File *flacFile,
        JMetadataBuilder &jBuilder) {
    auto id3v1Tag = flacFile->ID3v1Tag();
    if (id3v1Tag != nullptr) {
        try {
            jBuilder.setId3v1(*id3v1Tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse ID3v1 tag in %s: %s", name.c_str(), e.what());
        }
    }
    auto id3v2Tag = flacFile->ID3v2Tag();
    if (id3v2Tag != nullptr) {
        try {
            jBuilder.setId3v2(*id3v2Tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse ID3v2 tag in %s: %s", name.c_str(), e.what());
        }
    }
    auto xiphComment = flacFile->xiphComment();
    if (xiphComment != nullptr) {
        try {
            jBuilder.setXiph(*xiphComment);
        } catch (std::exception &e) {
            LOGE("Unable to parse Xiph comment in %s: %s", name.c_str(),
                    e.what());
        }
    }
    auto pics = flacFile->pictureList();
    jBuilder.setFlacPictures(pics);
    return true;
}

bool parseOpus(const std::string &name, TagLib::Ogg::Opus::File *opusFile,
        JMetadataBuilder &jBuilder) {
    auto tag = opusFile->tag();
    if (tag != nullptr) {
        try {
            jBuilder.setXiph(*tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse Xiph comment in %s: %s", name.c_str(),
                    e.what());
        }
    }
    return true;
}

bool parseVorbis(const std::string &name, TagLib::Ogg::Vorbis::File *vorbisFile,
        JMetadataBuilder &jBuilder) {
    auto tag = vorbisFile->tag();
    if (tag != nullptr) {
        try {
            jBuilder.setXiph(*tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse Xiph comment %s: %s", name.c_str(), e.what());
        }
    }
    return true;
}

bool parseWav(const std::string &name, TagLib::RIFF::WAV::File *wavFile,
        JMetadataBuilder &jBuilder) {
    auto tag = wavFile->ID3v2Tag();
    if (tag != nullptr) {
        try {
            jBuilder.setId3v2(*tag);
        } catch (std::exception &e) {
            LOGE("Unable to parse ID3v2 tag in %s: %s", name.c_str(), e.what());
        }
    }
    return true;
}

// A minimal implementation of 'detectByContent()' from TagLib,
// limited to the available 'parse..()' functions available here.
// This fixes mostly all parsing problems for formats undetected by
// simple extension based detection, like AAC-in-MP4, but still
// doesn't work for Matroska, as it is not yet supported by TagLib
// see https://github.com/taglib/taglib/pull/1149
// Detects the file type based on the actual content of the stream.
TagLib::File* createFileFromContent(TagLib::IOStream *stream,
        bool readAudioProperties,
        TagLib::AudioProperties::ReadStyle audioPropertiesStyle) {
    TagLib::File *file = nullptr;

    if (TagLib::MPEG::File::isSupported(stream))
        file = new TagLib::MPEG::File(stream, readAudioProperties,
                audioPropertiesStyle);
    else if (TagLib::Ogg::Vorbis::File::isSupported(stream))
        file = new TagLib::Ogg::Vorbis::File(stream, readAudioProperties,
                audioPropertiesStyle);
    else if (TagLib::FLAC::File::isSupported(stream))
        file = new TagLib::FLAC::File(stream, readAudioProperties,
                audioPropertiesStyle);
    else if (TagLib::Ogg::Opus::File::isSupported(stream))
        file = new TagLib::Ogg::Opus::File(stream, readAudioProperties,
                audioPropertiesStyle);
    else if (TagLib::MP4::File::isSupported(stream))
        file = new TagLib::MP4::File(stream, readAudioProperties,
                audioPropertiesStyle);
    else if (TagLib::RIFF::WAV::File::isSupported(stream))
        file = new TagLib::RIFF::WAV::File(stream, readAudioProperties,
                audioPropertiesStyle);

    // double-check that the potentially created file actually valid.
    if (file) {
        if (file->isValid())
            return file;
        delete file;
    }

    return nullptr;
}

// This dispatcher replaces the previous, order-dependent if-else chain. It uses
// dynamic_cast to identify the file's true type, ensuring the correct parser is
// always called. This also allows the 'parse..()' functions to be simplified by
// accepting a specific file type (e.g., 'MPEG::File*'), removing the need for
// redundant internal checks.
// Accurate container MIME type support is not added, as it did not seem
// necessary, we can rely on SAF and MediaStore implementation for now,
// that works on extension based detection, so we expect users to use
// correct file extensions.
bool dispatchAndParse(const std::string &name, TagLib::File *file,
        JMetadataBuilder &jBuilder) {
    if (auto *mpegFile = dynamic_cast<TagLib::MPEG::File*>(file)) {
        jBuilder.setMimeType("audio/mpeg");
        return parseMpeg(name, mpegFile, jBuilder);
    }
    if (auto *flacFile = dynamic_cast<TagLib::FLAC::File*>(file)) {
        jBuilder.setMimeType("audio/flac");
        return parseFlac(name, flacFile, jBuilder);
    }
    if (auto *opusFile = dynamic_cast<TagLib::Ogg::Opus::File*>(file)) {
        jBuilder.setMimeType("audio/opus");
        return parseOpus(name, opusFile, jBuilder);
    }
    if (auto *vorbisFile = dynamic_cast<TagLib::Ogg::Vorbis::File*>(file)) {
        jBuilder.setMimeType("audio/vorbis");
        return parseVorbis(name, vorbisFile, jBuilder);
    }
    if (auto *wavFile = dynamic_cast<TagLib::RIFF::WAV::File*>(file)) {
        jBuilder.setMimeType("audio/wav");
        return parseWav(name, wavFile, jBuilder);
    }
    if (auto *mp4File = dynamic_cast<TagLib::MP4::File*>(file)) {
        // Why are we setting this as default?
        // 'audio/mp4' is not even a codec MIME type.
        jBuilder.setMimeType("audio/mp4");
        if (auto *props =
                dynamic_cast<TagLib::MP4::Properties*>(mp4File->audioProperties())) {
            using Codec = TagLib::MP4::Properties::Codec;
            switch (props->codec()) {
            case Codec::AAC:
                jBuilder.setMimeType("audio/aac");
                break;
            case Codec::ALAC:
                jBuilder.setMimeType("audio/alac");
                break;
            default:
                break;
            }
        }
        return parseMp4(name, mp4File, jBuilder);
    }

    return false;
}

static jobject metadataResultSuccess(JNIEnv *env, jobject metadata) {
    JClassRef jSuccessClass { env,
            "org/oxycblt/musikr/metadata/MetadataResult$Success" };
    jmethodID jInitMethod = jSuccessClass.method("<init>",
            "(Lorg/oxycblt/musikr/metadata/Metadata;)V");
    return env->NewObject(*jSuccessClass, jInitMethod, metadata);
}

static jobject metadataResultObject(JNIEnv *env, const char *classpath) {
    JClassRef jObjectClass { env, classpath };
    std::string signature = std::string("L") + classpath + ";";
    jfieldID jInstanceField = env->GetStaticFieldID(*jObjectClass, "INSTANCE",
            signature.c_str());
    return env->GetStaticObjectField(*jObjectClass, jInstanceField);
}

static jobject metadataResultNoMetadata(JNIEnv *env) {
    return metadataResultObject(env,
            "org/oxycblt/musikr/metadata/MetadataResult$NoMetadata");
}

static jobject metadataResultNotAudio(JNIEnv *env) {
    return metadataResultObject(env,
            "org/oxycblt/musikr/metadata/MetadataResult$NotAudio");
}

static jobject metadataResultProviderFailed(JNIEnv *env) {
    return metadataResultObject(env,
            "org/oxycblt/musikr/metadata/MetadataResult$ProviderFailed");
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_oxycblt_musikr_metadata_TagLibJNI_openNative(JNIEnv *env,
        jobject /* this */,
        jobject inputStream) {
    std::string name = "unknown file";
    TagLib::File *overriddenFile = nullptr;
    try {
        JInputStream jStream {env, inputStream};
        name = jStream.name();
        TagLib::FileRef fileRef {&jStream, true, TagLib::AudioProperties::Average};
        TagLib::File *fileToUse = fileRef.file();
        bool needsOverride = (fileToUse != nullptr &&
                fileToUse->audioProperties() != nullptr &&
                fileToUse->audioProperties()->lengthInSeconds() == 0);
        if (needsOverride) {
            LOGD("FileRef result for %s is suspicious (duration=0). Forcing content scan.", name.c_str());
            jStream.seek(0, TagLib::IOStream::Beginning);
            overriddenFile = createFileFromContent(&jStream, true, TagLib::AudioProperties::Average);
            if (overriddenFile != nullptr &&
                    overriddenFile->audioProperties() != nullptr &&
                    overriddenFile->audioProperties()->lengthInSeconds() > 0)
            {
                LOGD("Content scan successful. Overriding FileRef result for %s.", name.c_str());
                fileToUse = overriddenFile;
            } else {
                delete overriddenFile;
                overriddenFile = nullptr;
            }
        }
        if (fileToUse == nullptr) {
            delete overriddenFile;
            return metadataResultNotAudio(env);
        }
        if (fileToUse->audioProperties() == nullptr) {
            LOGE("No audio properties for %s", name.c_str());
            delete overriddenFile;
            return metadataResultNoMetadata(env);
        }
        JMetadataBuilder jBuilder {env};
        jBuilder.setProperties(fileToUse->audioProperties());

        if (!dispatchAndParse(name, fileToUse, jBuilder)) {
            LOGE("File format in %s is not supported by any parser.", name.c_str());
            delete overriddenFile;
            return metadataResultNotAudio(env);
        }
        JObjectRef jMetadata {env, jBuilder.build()};
        delete overriddenFile;
        return metadataResultSuccess(env, *jMetadata);
    } catch (std::exception &e) {
        LOGE("Unable to parse metadata in %s: %s", name.c_str(), e.what());
        delete overriddenFile;
        return metadataResultProviderFailed(env);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_oxycblt_musikr_metadata_TagLibJNI_writeTagsNative(JNIEnv *env,
        jobject /* this */,
        jstring jpath,
        jstring jtitle,
        jstring jartist,
        jstring jalbum,
        jstring jgenre,
        jbyteArray jcoverData,
        jint jtrack,
        jint jyear) {
    if (jpath == nullptr) return JNI_FALSE;
    const char *pathChars = env->GetStringUTFChars(jpath, nullptr);
    if (pathChars == nullptr) return JNI_FALSE;

    const char *coverBytes = nullptr;
    jsize coverLen = 0;
    if (jcoverData != nullptr) {
        coverLen = env->GetArrayLength(jcoverData);
        if (coverLen > 0) {
            coverBytes = reinterpret_cast<const char*>(env->GetByteArrayElements(jcoverData, nullptr));
        }
    }

    bool success = false;
    try {
        TagLib::FileRef fileRef(pathChars, false);
        if (!fileRef.isNull() && fileRef.tag() != nullptr) {
            auto *tag = fileRef.tag();
            if (jtitle != nullptr) {
                const char *titleChars = env->GetStringUTFChars(jtitle, nullptr);
                tag->setTitle(TagLib::String(titleChars, TagLib::String::UTF8));
                env->ReleaseStringUTFChars(jtitle, titleChars);
            }
            if (jartist != nullptr) {
                const char *artistChars = env->GetStringUTFChars(jartist, nullptr);
                tag->setArtist(TagLib::String(artistChars, TagLib::String::UTF8));
                env->ReleaseStringUTFChars(jartist, artistChars);
            }
            if (jalbum != nullptr) {
                const char *albumChars = env->GetStringUTFChars(jalbum, nullptr);
                tag->setAlbum(TagLib::String(albumChars, TagLib::String::UTF8));
                env->ReleaseStringUTFChars(jalbum, albumChars);
            }
            if (jgenre != nullptr) {
                const char *genreChars = env->GetStringUTFChars(jgenre, nullptr);
                tag->setGenre(TagLib::String(genreChars, TagLib::String::UTF8));
                env->ReleaseStringUTFChars(jgenre, genreChars);
            }
            if (jtrack > 0) {
                tag->setTrack(static_cast<unsigned int>(jtrack));
            }
            if (jyear > 0) {
                tag->setYear(static_cast<unsigned int>(jyear));
            }

            if (coverBytes != nullptr && coverLen > 0) {
                TagLib::ByteVector byteVector(coverBytes, static_cast<unsigned int>(coverLen));
                bool isPng = (coverLen >= 4 &&
                              static_cast<unsigned char>(coverBytes[0]) == 0x89 &&
                              static_cast<unsigned char>(coverBytes[1]) == 0x50);

                if (auto *mp4File = dynamic_cast<TagLib::MP4::File*>(fileRef.file())) {
                    if (auto *mp4Tag = mp4File->tag()) {
                        TagLib::MP4::CoverArt::Format fmt = isPng ? TagLib::MP4::CoverArt::PNG : TagLib::MP4::CoverArt::JPEG;
                        TagLib::MP4::CoverArt art(fmt, byteVector);
                        TagLib::MP4::CoverArtList list;
                        list.append(art);
                        mp4Tag->setItem("covr", TagLib::MP4::Item(list));
                    }
                } else if (auto *mpegFile = dynamic_cast<TagLib::MPEG::File*>(fileRef.file())) {
                    if (auto *id3v2Tag = mpegFile->ID3v2Tag(true)) {
                        auto frames = id3v2Tag->frameList("APIC");
                        for (auto *f : frames) {
                            id3v2Tag->removeFrame(f);
                        }
                        auto *frame = new TagLib::ID3v2::AttachedPictureFrame();
                        frame->setPicture(byteVector);
                        frame->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);
                        frame->setMimeType(isPng ? "image/png" : "image/jpeg");
                        id3v2Tag->addFrame(frame);
                    }
                } else if (auto *flacFile = dynamic_cast<TagLib::FLAC::File*>(fileRef.file())) {
                    flacFile->removePictures();
                    auto *pic = new TagLib::FLAC::Picture();
                    pic->setData(byteVector);
                    pic->setType(TagLib::FLAC::Picture::FrontCover);
                    pic->setMimeType(isPng ? "image/png" : "image/jpeg");
                    flacFile->addPicture(pic);
                } else if (auto *opusFile = dynamic_cast<TagLib::Ogg::Opus::File*>(fileRef.file())) {
                    if (auto *opusTag = opusFile->tag()) {
                        opusTag->removeAllPictures();
                        auto *pic = new TagLib::FLAC::Picture();
                        pic->setData(byteVector);
                        pic->setType(TagLib::FLAC::Picture::FrontCover);
                        pic->setMimeType(isPng ? "image/png" : "image/jpeg");
                        opusTag->addPicture(pic);
                    }
                }
            }

            success = fileRef.save();
            LOGD("writeTagsNative for %s: success=%d", pathChars, success);
        } else {
            LOGE("writeTagsNative: fileRef isNull or tag is null for %s", pathChars);
        }
    } catch (std::exception &e) {
        LOGE("writeTagsNative exception for %s: %s", pathChars, e.what());
    }

    if (coverBytes != nullptr) {
        env->ReleaseByteArrayElements(jcoverData, reinterpret_cast<jbyte*>(const_cast<char*>(coverBytes)), JNI_ABORT);
    }
    env->ReleaseStringUTFChars(jpath, pathChars);
    return success ? JNI_TRUE : JNI_FALSE;
}
