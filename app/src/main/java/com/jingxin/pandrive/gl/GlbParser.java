package com.jingxin.pandrive.gl;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 多 Mesh GLB 解析器
 * 支持：多 mesh / 多 primitive / 多材质 / 多纹理 / 法线 / 节点树变换
 */
public class GlbParser {

    private static final String TAG = "GlbParser";

    /** 单个绘制单元，对应一个 primitive */
    public static class DrawUnit {
        public FloatBuffer vertexBuffer;  // pos(3)+normal(3)+uv(2) = 8 floats
        public int vertexCount;
        public int textureId = -1;        // GL 纹理 ID，由渲染器上传后设置
        public Bitmap pendingBitmap;      // 待上传纹理
        public boolean hasTexture;
        public boolean isTransparent;     // 透明物体（玻璃）
        public float[] baseColorFactor;   // RGBA 基色因子，无纹理时使用
        public String materialName;       // 材质名称，用于语义着色
        public float[] nodeTransform;     // 该 mesh 在节点树中的累积变换矩阵
        public boolean isLight = false;    // 是否为灯部件（shader中根据世界X坐标判断左右）
        public float[] cachedColor;       // 缓存计算后的有效颜色，避免每帧重新分配float[]
    }

    /** 解析结果 */
    public final List<DrawUnit> drawUnits = new ArrayList<>();
    public float[] boundsMin;
    public float[] boundsMax;

    // 内部缓存
    private JSONArray accessorsArr;
    private JSONArray bufferViewsArr;
    private byte[] binaryData;

    public boolean parse(InputStream is) {
        try {
            byte[] glbData = readAll(is);
            return parse(glbData);
        } catch (Exception e) {
            Log.e(TAG, "GLB 解析失败", e);
            return false;
        }
    }

    public boolean parse(byte[] glbData) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(glbData).order(ByteOrder.LITTLE_ENDIAN);

            int magic = bb.getInt();
            int version = bb.getInt();
            int totalLength = bb.getInt();
            if (magic != 0x46546C67) {
                Log.e(TAG, "不是有效的 GLB 文件");
                return false;
            }

            String jsonStr = null;
            binaryData = null;
            while (bb.remaining() >= 8) {
                int chunkLength = bb.getInt();
                int chunkType = bb.getInt();
                byte[] chunkData = new byte[chunkLength];
                bb.get(chunkData);
                if (chunkType == 0x4E4F534A) jsonStr = new String(chunkData, "UTF-8");
                else if (chunkType == 0x004E4942) binaryData = chunkData;
            }
            if (jsonStr == null || binaryData == null) return false;

            JSONObject gltf = new JSONObject(jsonStr);
            accessorsArr = gltf.getJSONArray("accessors");
            bufferViewsArr = gltf.getJSONArray("bufferViews");

            JSONArray meshes = gltf.getJSONArray("meshes");
            JSONArray materials = gltf.optJSONArray("materials");
            JSONArray textures = gltf.optJSONArray("textures");
            JSONArray images = gltf.optJSONArray("images");
            JSONArray nodes = gltf.optJSONArray("nodes");

            // 全局包围盒
            computeGlobalBounds(accessorsArr, meshes);

            // 构建节点变换映射: nodeIndex -> 累积变换矩阵
            float[][] nodeTransforms = buildNodeTransforms(nodes, gltf.optJSONArray("scenes"));

            // 解析纹理缓存: imageIndex -> Bitmap
            Bitmap[] imageBitmaps = new Bitmap[images != null ? images.length() : 0];

            // 遍历所有 mesh
            for (int mi = 0; mi < meshes.length(); mi++) {
                JSONObject mesh = meshes.getJSONObject(mi);
                String meshName = mesh.optString("name", "mesh_" + mi);
                JSONArray prims = mesh.getJSONArray("primitives");

                for (int pi = 0; pi < prims.length(); pi++) {
                    JSONObject prim = prims.getJSONObject(pi);
                    DrawUnit unit = new DrawUnit();
                    unit.materialName = meshName;

                    JSONObject attrs = prim.getJSONObject("attributes");
                    int posIdx = attrs.getInt("POSITION");
                    int normalIdx = attrs.optInt("NORMAL", -1);
                    int uvIdx = attrs.optInt("TEXCOORD_0", -1);
                    int indicesIdx = prim.optInt("indices", -1);

                    Accessor posAcc = readAccessor(accessorsArr.getJSONObject(posIdx));
                    Accessor normAcc = (normalIdx >= 0) ? readAccessor(accessorsArr.getJSONObject(normalIdx)) : null;
                    Accessor uvAcc = (uvIdx >= 0) ? readAccessor(accessorsArr.getJSONObject(uvIdx)) : null;
                    Accessor idxAcc = (indicesIdx >= 0) ? readAccessor(accessorsArr.getJSONObject(indicesIdx)) : null;

                    // 展开: pos(3) + normal(3) + uv(2) = 8 floats/vertex
                    float[] verts;
                    if (idxAcc != null) {
                        unit.vertexCount = idxAcc.count;
                        verts = expandIndexed(posAcc, normAcc, uvAcc, idxAcc);
                    } else {
                        unit.vertexCount = posAcc.count;
                        verts = expandUnindexed(posAcc, normAcc, uvAcc);
                    }

                    // 材质
                    int matIdx = prim.optInt("material", -1);
                    if (matIdx >= 0 && materials != null && matIdx < materials.length()) {
                        JSONObject mat = materials.getJSONObject(matIdx);
                        unit.materialName = mat.optString("name", unit.materialName);
                        unit.isTransparent = isMaterialTransparent(mat);
                        unit.baseColorFactor = readBaseColorFactor(mat);

                        // baseColor 纹理
                        JSONObject pbr = mat.optJSONObject("pbrMetallicRoughness");
                        if (pbr != null) {
                            JSONObject texInfo = pbr.optJSONObject("baseColorTexture");
                            if (texInfo != null && textures != null && images != null) {
                                Bitmap bmp = extractTexture(texInfo, textures, images, imageBitmaps);
                                if (bmp != null) {
                                    unit.pendingBitmap = bmp;
                                    unit.hasTexture = true;
                                }
                            }
                        }
                        // emissive 纹理作为备选（灯光发光部件无 baseColor 时）
                        if (!unit.hasTexture) {
                            JSONObject emissiveTex = mat.optJSONObject("emissiveTexture");
                            if (emissiveTex != null && textures != null && images != null) {
                                Bitmap bmp = extractTexture(emissiveTex, textures, images, imageBitmaps);
                                if (bmp != null) {
                                    unit.pendingBitmap = bmp;
                                    unit.hasTexture = true;
                                }
                            }
                        }
                    }

                    if (!unit.hasTexture && unit.baseColorFactor == null) {
                        unit.baseColorFactor = new float[]{0.8f, 0.8f, 0.8f, 1f};
                    }

                    // 节点变换
                    unit.nodeTransform = findNodeTransformForMesh(mi, nodes, nodeTransforms);

                    // 烘焙节点变换到顶点数据（在float[]上操作，安全可靠）
                    if (unit.nodeTransform != null && !isIdentity(unit.nodeTransform)) {
                        bakeNodeTransform(verts, unit.vertexCount, unit.nodeTransform);
                    }
                    unit.nodeTransform = null; // 已烘焙，不再需要

                    // 从烘焙后的float[]创建FloatBuffer
                    unit.vertexBuffer = ByteBuffer.allocateDirect(verts.length * 4)
                            .order(ByteOrder.nativeOrder()).asFloatBuffer();
                    unit.vertexBuffer.put(verts).flip();

                    drawUnits.add(unit);
                }
            }

            // 烘焙顶点后重新计算包围盒（因为节点变换改变了顶点位置）
            recomputeBounds();
            Log.i(TAG, "解析完成: " + drawUnits.size() + " drawUnits, 包围盒=["
                    + boundsMin[0] + "," + boundsMin[1] + "," + boundsMin[2] + "]~["
                    + boundsMax[0] + "," + boundsMax[1] + "," + boundsMax[2] + "]");
            return !drawUnits.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "GLB 解析异常", e);
            return false;
        }
    }

    // ==================== 包围盒 ====================

    private void computeGlobalBounds(JSONArray accessors, JSONArray meshes) throws Exception {
        float gMinX = Float.MAX_VALUE, gMinY = Float.MAX_VALUE, gMinZ = Float.MAX_VALUE;
        float gMaxX = -Float.MAX_VALUE, gMaxY = -Float.MAX_VALUE, gMaxZ = -Float.MAX_VALUE;
        for (int mi = 0; mi < meshes.length(); mi++) {
            JSONObject mesh = meshes.getJSONObject(mi);
            JSONArray prims = mesh.getJSONArray("primitives");
            for (int pi = 0; pi < prims.length(); pi++) {
                int posIdx = prims.getJSONObject(pi).getJSONObject("attributes").getInt("POSITION");
                JSONArray mn = accessors.getJSONObject(posIdx).optJSONArray("min");
                JSONArray mx = accessors.getJSONObject(posIdx).optJSONArray("max");
                if (mn != null && mx != null) {
                    gMinX = Math.min(gMinX, (float) mn.optDouble(0));
                    gMinY = Math.min(gMinY, (float) mn.optDouble(1));
                    gMinZ = Math.min(gMinZ, (float) mn.optDouble(2));
                    gMaxX = Math.max(gMaxX, (float) mx.optDouble(0));
                    gMaxY = Math.max(gMaxY, (float) mx.optDouble(1));
                    gMaxZ = Math.max(gMaxZ, (float) mx.optDouble(2));
                }
            }
        }
        boundsMin = new float[]{gMinX, gMinY, gMinZ};
        boundsMax = new float[]{gMaxX, gMaxY, gMaxZ};
    }

    /** 从烘焙后的顶点数据重新计算全局包围盒 */
    private void recomputeBounds() {
        float gMinX = Float.MAX_VALUE, gMinY = Float.MAX_VALUE, gMinZ = Float.MAX_VALUE;
        float gMaxX = -Float.MAX_VALUE, gMaxY = -Float.MAX_VALUE, gMaxZ = -Float.MAX_VALUE;
        for (DrawUnit unit : drawUnits) {
            unit.vertexBuffer.position(0);
            for (int i = 0; i < unit.vertexCount; i++) {
                int base = i * 8;
                float x = unit.vertexBuffer.get(base);
                float y = unit.vertexBuffer.get(base + 1);
                float z = unit.vertexBuffer.get(base + 2);
                if (x < gMinX) gMinX = x; if (x > gMaxX) gMaxX = x;
                if (y < gMinY) gMinY = y; if (y > gMaxY) gMaxY = y;
                if (z < gMinZ) gMinZ = z; if (z > gMaxZ) gMaxZ = z;
            }
        }
        boundsMin = new float[]{gMinX, gMinY, gMinZ};
        boundsMax = new float[]{gMaxX, gMaxY, gMaxZ};
    }

    // ==================== 节点树 ====================

    private float[][] buildNodeTransforms(JSONArray nodes, JSONArray scenes) throws Exception {
        int n = nodes.length();
        float[][] transforms = new float[n][];
        boolean[] visited = new boolean[n];

        // 获取根节点
        List<Integer> roots = new ArrayList<>();
        if (scenes != null && scenes.length() > 0) {
            JSONArray rootNodes = scenes.getJSONObject(0).optJSONArray("nodes");
            if (rootNodes != null) {
                for (int i = 0; i < rootNodes.length(); i++) roots.add(rootNodes.getInt(i));
            }
        }
        if (roots.isEmpty() && n > 0) roots.add(0);

        float[] identity = new float[16];
        android.opengl.Matrix.setIdentityM(identity, 0);
        for (int r : roots) traverseNode(r, nodes, transforms, visited, identity);
        // 未被遍历的节点赋单位矩阵
        for (int i = 0; i < n; i++) {
            if (transforms[i] == null) {
                transforms[i] = new float[16];
                android.opengl.Matrix.setIdentityM(transforms[i], 0);
            }
        }
        return transforms;
    }

    private void traverseNode(int idx, JSONArray nodes, float[][] transforms, boolean[] visited, float[] parentMat) throws Exception {
        if (visited[idx]) return;
        visited[idx] = true;

        JSONObject node = nodes.getJSONObject(idx);
        float[] localMat = new float[16];
        if (node.has("matrix")) {
            JSONArray m = node.getJSONArray("matrix");
            // glTF 矩阵是列主序，Android Matrix 也是列主序，直接使用无需转置
            localMat = new float[16];
            for (int i = 0; i < 16; i++) localMat[i] = (float) m.optDouble(i);
        } else {
            // TRS
            android.opengl.Matrix.setIdentityM(localMat, 0);
            if (node.has("scale")) {
                JSONArray s = node.getJSONArray("scale");
                localMat[0] = (float) s.optDouble(0);
                localMat[5] = (float) s.optDouble(1);
                localMat[10] = (float) s.optDouble(2);
            }
            if (node.has("rotation")) {
                JSONArray q = node.getJSONArray("rotation");
                float qx = (float) q.optDouble(0), qy = (float) q.optDouble(1);
                float qz = (float) q.optDouble(2), qw = (float) q.optDouble(3);
                float[] rotMat = new float[16];
                quaternionToMatrix(qx, qy, qz, qw, rotMat);
                float[] tmp = new float[16];
                android.opengl.Matrix.multiplyMM(tmp, 0, rotMat, 0, localMat.clone(), 0);
                localMat = tmp;
            }
            if (node.has("translation")) {
                JSONArray t = node.getJSONArray("translation");
                localMat[12] = (float) t.optDouble(0);
                localMat[13] = (float) t.optDouble(1);
                localMat[14] = (float) t.optDouble(2);
            }
        }

        float[] worldMat = new float[16];
        android.opengl.Matrix.multiplyMM(worldMat, 0, parentMat, 0, localMat, 0);
        transforms[idx] = worldMat;

        JSONArray children = node.optJSONArray("children");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                traverseNode(children.getInt(i), nodes, transforms, visited, worldMat);
            }
        }
    }

    private static void quaternionToMatrix(float qx, float qy, float qz, float qw, float[] out) {
        float qx2 = qx * qx;
        float qy2 = qy * qy;
        float qz2 = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx = qw * qx, wy = qw * qy, wz = qw * qz;
        android.opengl.Matrix.setIdentityM(out, 0);
        out[0] = 1 - 2 * (qy2 + qz2);  out[4] = 2 * (xy - wz);      out[8] = 2 * (xz + wy);
        out[1] = 2 * (xy + wz);      out[5] = 1 - 2 * (qx2 + qz2);  out[9] = 2 * (yz - wx);
        out[2] = 2 * (xz - wy);      out[6] = 2 * (yz + wx);      out[10] = 1 - 2 * (qx2 + qy2);
    }

    /** 找到引用某个 mesh 的节点的累积变换 */
    private float[] findNodeTransformForMesh(int meshIdx, JSONArray nodes, float[][] nodeTransforms) throws Exception {
        for (int ni = 0; ni < nodes.length(); ni++) {
            JSONObject node = nodes.getJSONObject(ni);
            if (node.optInt("mesh", -1) == meshIdx && nodeTransforms[ni] != null) {
                return nodeTransforms[ni];
            }
        }
        float[] identity = new float[16];
        android.opengl.Matrix.setIdentityM(identity, 0);
        return identity;
    }

    // ==================== 纹理提取 ====================

    private Bitmap extractTexture(JSONObject texInfo, JSONArray textures, JSONArray images, Bitmap[] cache) throws Exception {
        int texIdx = texInfo.getInt("index");
        JSONObject texture = textures.getJSONObject(texIdx);
        int imageIdx = texture.getInt("source");
        if (cache[imageIdx] != null) return cache[imageIdx];

        JSONObject image = images.getJSONObject(imageIdx);
        int bvIdx = image.getInt("bufferView");
        JSONObject bv = bufferViewsArr.getJSONObject(bvIdx);
        int imgOffset = bv.optInt("byteOffset", 0);
        int imgLength = bv.getInt("byteLength");

        byte[] imgData = new byte[imgLength];
        System.arraycopy(binaryData, imgOffset, imgData, 0, imgLength);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(imgData, 0, imgData.length, opts);
        cache[imageIdx] = bmp;
        return bmp;
    }

    // ==================== 材质 ====================

    private float[] readBaseColorFactor(JSONObject mat) throws Exception {
        JSONObject pbr = mat.optJSONObject("pbrMetallicRoughness");
        if (pbr != null) {
            JSONArray bcf = pbr.optJSONArray("baseColorFactor");
            if (bcf != null && bcf.length() >= 4) {
                return new float[]{(float) bcf.optDouble(0), (float) bcf.optDouble(1),
                        (float) bcf.optDouble(2), (float) bcf.optDouble(3)};
            }
        }
        // 发光因子作为备选颜色
        JSONArray ef = mat.optJSONArray("emissiveFactor");
        if (ef != null && ef.length() >= 3) {
            return new float[]{(float) ef.optDouble(0), (float) ef.optDouble(1),
                    (float) ef.optDouble(2), 1f};
        }
        return null;
    }

    private boolean isMaterialTransparent(JSONObject mat) throws Exception {
        String alphaMode = mat.optString("alphaMode", "OPAQUE");
        if ("BLEND".equals(alphaMode)) return true;
        // 有 transmission 扩展的材质视为透明（玻璃）
        JSONObject ext = mat.optJSONObject("extensions");
        if (ext != null && ext.has("KHR_materials_transmission")) return true;
        // 名称含 Glass/glass/玻璃 的也视为透明
        String name = mat.optString("name", "");
        if (name.toLowerCase().contains("glass") || name.contains("玻璃")) return true;
        return false;
    }

    // ==================== 顶点展开 ====================

    private float[] expandIndexed(Accessor pos, Accessor norm, Accessor uv, Accessor idx) {
        int fpv = 8; // pos(3)+normal(3)+uv(2)
        float[] out = new float[idx.count * fpv];
        int pc = getTypeComponents(pos.type);
        int nc = (norm != null) ? getTypeComponents(norm.type) : 0;
        int uc = (uv != null) ? getTypeComponents(uv.type) : 0;

        for (int i = 0; i < idx.count; i++) {
            int vi = idx.intData[i];
            int base = i * fpv;
            out[base]   = pos.data[vi * pc];
            out[base+1] = pos.data[vi * pc + 1];
            out[base+2] = pos.data[vi * pc + 2];
            if (norm != null && nc >= 3) {
                out[base+3] = norm.data[vi * nc];
                out[base+4] = norm.data[vi * nc + 1];
                out[base+5] = norm.data[vi * nc + 2];
            } else {
                out[base+3] = 0; out[base+4] = 1; out[base+5] = 0;
            }
            if (uv != null && uc >= 2) {
                out[base+6] = uv.data[vi * uc];
                out[base+7] = uv.data[vi * uc + 1];
            } else {
                out[base+6] = 0; out[base+7] = 0;
            }
        }
        return out;
    }

    private float[] expandUnindexed(Accessor pos, Accessor norm, Accessor uv) {
        int fpv = 8;
        int n = pos.count;
        float[] out = new float[n * fpv];
        int pc = getTypeComponents(pos.type);
        int nc = (norm != null) ? getTypeComponents(norm.type) : 0;
        int uc = (uv != null) ? getTypeComponents(uv.type) : 0;

        for (int i = 0; i < n; i++) {
            int base = i * fpv;
            out[base]   = pos.data[i * pc];
            out[base+1] = pos.data[i * pc + 1];
            out[base+2] = pos.data[i * pc + 2];
            if (norm != null && nc >= 3) {
                out[base+3] = norm.data[i * nc];
                out[base+4] = norm.data[i * nc + 1];
                out[base+5] = norm.data[i * nc + 2];
            } else {
                out[base+3] = 0; out[base+4] = 1; out[base+5] = 0;
            }
            if (uv != null && uc >= 2) {
                out[base+6] = uv.data[i * uc];
                out[base+7] = uv.data[i * uc + 1];
            } else {
                out[base+6] = 0; out[base+7] = 0;
            }
        }
        return out;
    }

    // ==================== Accessor 读取 ====================

    private static class Accessor {
        int count;
        int componentType;
        String type;
        float[] data;
        int[] intData;
    }

    private Accessor readAccessor(JSONObject acc) throws Exception {
        Accessor r = new Accessor();
        r.count = acc.getInt("count");
        r.componentType = acc.getInt("componentType");
        r.type = acc.getString("type");

        int bvIdx = acc.getInt("bufferView");
        int accOff = acc.optInt("byteOffset", 0);
        JSONObject bv = bufferViewsArr.getJSONObject(bvIdx);
        int bvOff = bv.optInt("byteOffset", 0);
        int bvLen = bv.getInt("byteLength");
        int totalOff = bvOff + accOff;

        if ("SCALAR".equals(r.type)) {
            int elemSize = getComponentSize(r.componentType);
            int cnt = bvLen / elemSize;
            r.intData = new int[cnt];
            ByteBuffer src = ByteBuffer.wrap(binaryData, totalOff, bvLen).order(ByteOrder.LITTLE_ENDIAN);
            switch (r.componentType) {
                case 5121: for (int i = 0; i < cnt; i++) r.intData[i] = src.get() & 0xFF; break;
                case 5123: for (int i = 0; i < cnt; i++) r.intData[i] = src.getShort() & 0xFFFF; break;
                case 5125: for (int i = 0; i < cnt; i++) r.intData[i] = src.getInt(); break;
            }
        } else {
            int numComp = getTypeComponents(r.type);
            int elemCount = bvLen / getComponentSize(r.componentType);
            r.data = new float[elemCount];
            ByteBuffer src = ByteBuffer.wrap(binaryData, totalOff, bvLen).order(ByteOrder.LITTLE_ENDIAN);
            switch (r.componentType) {
                case 5120: for (int i = 0; i < elemCount; i++) r.data[i] = src.get(); break;
                case 5121: for (int i = 0; i < elemCount; i++) r.data[i] = (src.get() & 0xFF); break;
                case 5122: for (int i = 0; i < elemCount; i++) r.data[i] = src.getShort(); break;
                case 5123: for (int i = 0; i < elemCount; i++) r.data[i] = (src.getShort() & 0xFFFF); break;
                case 5126: for (int i = 0; i < elemCount; i++) r.data[i] = src.getFloat(); break;
            }
        }
        return r;
    }

    private static int getComponentSize(int ct) {
        switch (ct) {
            case 5120: case 5121: return 1;
            case 5122: case 5123: return 2;
            case 5125: case 5126: return 4;
            default: return 4;
        }
    }

    private static int getTypeComponents(String type) {
        switch (type) {
            case "SCALAR": return 1;
            case "VEC2":   return 2;
            case "VEC3":   return 3;
            case "VEC4":   return 4;
            case "MAT2":   return 4;
            case "MAT3":   return 9;
            case "MAT4":   return 16;
            default: return 1;
        }
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int len;
        while ((len = is.read(tmp)) != -1) buf.write(tmp, 0, len);
        is.close();
        return buf.toByteArray();
    }

    /** 烘焙节点变换到顶点float[]数组 */
    private static void bakeNodeTransform(float[] verts, int vertCount, float[] m) {
        for (int i = 0; i < vertCount; i++) {
            int base = i * 8;
            float vx = verts[base], vy = verts[base+1], vz = verts[base+2];
            // 变换位置: m * [x,y,z,1]
            verts[base]   = m[0]*vx + m[4]*vy + m[8]*vz  + m[12];
            verts[base+1] = m[1]*vx + m[5]*vy + m[9]*vz  + m[13];
            verts[base+2] = m[2]*vx + m[6]*vy + m[10]*vz + m[14];
            // 变换法线: 只用旋转/缩放部分（忽略平移）
            float nx = verts[base+3], ny = verts[base+4], nz = verts[base+5];
            float tnx = m[0]*nx + m[4]*ny + m[8]*nz;
            float tny = m[1]*nx + m[5]*ny + m[9]*nz;
            float tnz = m[2]*nx + m[6]*ny + m[10]*nz;
            float len = (float)Math.sqrt(tnx*tnx + tny*tny + tnz*tnz);
            if (len > 0.0001f) { tnx /= len; tny /= len; tnz /= len; }
            verts[base+3] = tnx; verts[base+4] = tny; verts[base+5] = tnz;
        }
    }

    private static boolean isIdentity(float[] m) {
        return Math.abs(m[0]-1)<0.001f && Math.abs(m[5]-1)<0.001f && Math.abs(m[10]-1)<0.001f
                && Math.abs(m[15]-1)<0.001f && Math.abs(m[12])<0.001f && Math.abs(m[13])<0.001f
                && Math.abs(m[14])<0.001f;
    }

    public void release() {
        for (DrawUnit u : drawUnits) {
            if (u.pendingBitmap != null && !u.pendingBitmap.isRecycled()) {
                u.pendingBitmap.recycle();
                u.pendingBitmap = null;
            }
        }
        drawUnits.clear();
    }
}
